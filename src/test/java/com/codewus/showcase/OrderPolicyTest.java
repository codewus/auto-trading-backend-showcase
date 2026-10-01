package com.codewus.showcase;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import com.codewus.showcase.api.DemoOrderController;
import com.codewus.showcase.exchange.FakeExchangeAdapter;
import com.codewus.showcase.order.OrderHistoryService;
import com.codewus.showcase.order.RecoveryService;
import com.codewus.showcase.order.model.ActionConflictException;
import com.codewus.showcase.order.model.OrderCommand;
import com.codewus.showcase.order.model.OrderIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderPolicyTest {
    UnitFixture f;
    OrderCommand command;

    @BeforeEach void setUp() {
        f = new UnitFixture();
        command = new OrderCommand("demo-actor", "action-1", "DEMOUSDT", OrderCommand.Side.BUY, new BigDecimal("2.50"));
    }

    @Test void sameActionReusesFillWithoutAnotherSubmit() {
        var first = f.service().submit(command);
        var second = f.service().submit(command);
        assertThat(second.fill().id()).isEqualTo(first.fill().id());
        assertThat(f.exchange.submitCount(command.clientOrderId())).isEqualTo(1);
        assertThat(f.stateChanges).isEqualTo(1);
    }

    @Test void changedQuantityConflictsBeforeExchangeCall() {
        f.service().submit(command);
        var changed = new OrderCommand(command.actorId(), command.actionId(), command.symbol(), command.side(), BigDecimal.ONE);
        assertThatThrownBy(() -> f.service().submit(changed)).isInstanceOf(ActionConflictException.class);
        assertThat(f.exchange.submitCount(command.clientOrderId())).isEqualTo(1);
    }

    @Test void changedSymbolOrSideAlsoConflicts() {
        f.service().submit(command);
        assertThatThrownBy(() -> f.service().submit(new OrderCommand(command.actorId(), command.actionId(),
                "OTHERUSDT", command.side(), command.quantity()))).isInstanceOf(ActionConflictException.class);
        assertThatThrownBy(() -> f.service().submit(new OrderCommand(command.actorId(), command.actionId(),
                command.symbol(), OrderCommand.Side.SELL, command.quantity()))).isInstanceOf(ActionConflictException.class);
    }

    @Test void equivalentDecimalUsesSameFingerprint() {
        f.service().submit(command);
        var equivalent = new OrderCommand(command.actorId(), command.actionId(), command.symbol(), command.side(), new BigDecimal("2.5000"));
        assertThat(f.service().submit(equivalent).locallyApplied()).isTrue();
        assertThat(f.exchange.submitCount(command.clientOrderId())).isEqualTo(1);
    }

    @Test void lostResponseQueriesSameClientOrderId() {
        f.exchange.selectScenario(command.actorId(), command.actionId(), FakeExchangeAdapter.Scenario.RESPONSE_LOST);
        var result = f.service().submit(command);
        assertThat(result.order().status()).isEqualTo(OrderIntent.Status.CONFIRMED);
        assertThat(result.fill()).isNotNull();
        assertThat(f.exchange.submitCount(command.clientOrderId())).isEqualTo(1);
        assertThat(f.exchange.queryCount(command.clientOrderId())).isEqualTo(1);
    }

    @Test void unknownResultNeverAutomaticallyResubmits() {
        f.exchange.selectScenario(command.actorId(), command.actionId(), FakeExchangeAdapter.Scenario.UNKNOWN);
        f.service().submit(command);
        for (int i = 0; i < 3; i++) f.service().submit(command);
        new RecoveryService(f.orders, f.service()).recover(command.actorId());
        assertThat(f.exchange.submitCount(command.clientOrderId())).isEqualTo(1);
        assertThat(f.exchange.queryCount(command.clientOrderId())).isEqualTo(4);
        assertThat(f.storedFills).isEmpty();
        assertThat(f.applied).isEmpty();
    }

    @Test void claimLoserOnlyQueriesEvenIfOwnerHasNotSubmitted() {
        f.orders.insertIntent(command, command.clientOrderId(), command.fingerprint());
        f.claims.add(1L); // Owner stopped after durable claim, before dispatch.
        assertThat(f.service().submit(command).fill()).isNull();
        assertThat(f.exchange.submitCount(command.clientOrderId())).isZero();
        assertThat(f.exchange.queryCount(command.clientOrderId())).isEqualTo(1);
    }

    @Test void repeatedExchangeResultDoesNotDuplicateAggregateFill() {
        f.exchange.selectScenario(command.actorId(), command.actionId(), FakeExchangeAdapter.Scenario.REPEATED_RESULT);
        var view = f.service().submit(command);
        for (int i = 0; i < 3; i++) f.recorder.record(view.order(), f.exchange.query(view.order()));
        assertThat(f.storedFills).hasSize(1);
        assertThat(f.exchange.submitCount(command.clientOrderId())).isEqualTo(1);
    }

    @Test void transientEventFailureKeepsFillAndRetriesOnlyMissingEvent() {
        f.exchange.selectScenario(command.actorId(), command.actionId(), FakeExchangeAdapter.Scenario.EVENT_FAILURE);
        var first = f.service().submit(command);
        assertThat(first.eventCount()).isEqualTo(1);
        assertThat(first.locallyApplied()).isTrue();
        clearInvocations(f.events);
        var recovered = new RecoveryService(f.orders, f.service()).recover(command.actorId()).get(0);
        assertThat(recovered.eventCount()).isEqualTo(2);
        verify(f.events).insert(first.fill().id(), "ORDER_CONFIRMED");
        verify(f.events, never()).insert(first.fill().id(), "FILL_RECORDED");
        assertThat(f.stateChanges).isEqualTo(1);
    }

    @Test void newServiceInstancesKeepExchangeStateAndDoNotReapplyFill() {
        var view = f.service().submit(command);
        f.storedEvents.clear();
        var restarted = f.service();
        new RecoveryService(f.orders, restarted).recover(command.actorId());
        new RecoveryService(f.orders, restarted).recover(command.actorId());
        assertThat(f.exchange.query(view.order()).confirmed()).isTrue();
        assertThat(f.stateChanges).isEqualTo(1);
        assertThat(f.signedQuantity).isEqualByComparingTo(command.quantity());
        assertThat(f.storedEvents).hasSize(2);
    }

    @Test void explicitRecoveryAppliesPreviouslySavedUnappliedFill() {
        var view = f.service().submit(command);
        f.applied.clear(); f.stateChanges = 0; f.signedQuantity = BigDecimal.ZERO;
        new RecoveryService(f.orders, f.service()).recover(command.actorId());
        assertThat(f.applied).containsExactly(view.fill().id());
        assertThat(f.stateChanges).isEqualTo(1);
    }

    @Test void recoveryQueriesAcceptedExchangeOrderWhoseFillWasNeverSaved() {
        f.orders.insertIntent(command, command.clientOrderId(), command.fingerprint());
        var intent = f.orders.findAction(command.actorId(), command.actionId());
        f.orders.claim(intent);
        f.exchange.submit(intent); // Process boundary simulated before the local result write.
        var recovered = new RecoveryService(f.orders, f.service()).recover(command.actorId()).get(0);
        assertThat(recovered.fill()).isNotNull();
        assertThat(recovered.locallyApplied()).isTrue();
        assertThat(recovered.eventCount()).isEqualTo(2);
        assertThat(f.exchange.submitCount(command.clientOrderId())).isEqualTo(1);
        assertThat(f.exchange.queryCount(command.clientOrderId())).isEqualTo(1);
    }

    @Test void getControllerNeverRepairsMissingLocalWork() {
        var view = f.service().submit(command);
        f.applied.clear(); f.storedEvents.clear();
        var controller = new DemoOrderController(f.service(), new OrderHistoryService(f.fills, f.legacy),
                new RecoveryService(f.orders, f.service()), f.exchange);
        clearInvocations(f.orders, f.fills, f.applications, f.events, f.legacy);
        assertThat(controller.get(view.order().id(), command.actorId()).locallyApplied()).isFalse();
        assertThat(controller.history(command.actorId(), 20)).isEmpty();
        verify(f.orders, never()).claim(any());
        verify(f.orders, never()).markUncertain(anyLong());
        verify(f.fills, never()).insert(any(), any(), any());
        verify(f.applications, never()).updateState(any());
        verify(f.applications, never()).markApplied(anyLong());
        verify(f.events, never()).insert(anyLong(), anyString());
        verify(f.legacy, never()).insert(any(), any());
        assertThat(f.exchange.queryCount(command.clientOrderId())).isZero();
        assertThat(f.applied).isEmpty();
        assertThat(f.storedEvents).isEmpty();
    }

    @Test void actorScopePreventsReadingAnotherActorsOrder() {
        var view = f.service().submit(command);
        assertThatThrownBy(() -> f.service().get("demo-other", view.order().id())).isInstanceOf(NoSuchElementException.class);
    }

    @Test void identityAndQuantityValidationRejectAmbiguousInputs() {
        assertThatThrownBy(() -> new OrderCommand("bad|actor", "a", "DEMOUSDT", OrderCommand.Side.BUY, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCommand("demo", "a", "DEMOUSDT", OrderCommand.Side.BUY, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCommand("demo", "a", "DEMOUSDT", OrderCommand.Side.BUY, new BigDecimal("0.000000001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCommand("demo", "a", "DEMOUSDT", OrderCommand.Side.BUY, new BigDecimal("1E+2147483647"))).isInstanceOf(IllegalArgumentException.class);
    }
}
