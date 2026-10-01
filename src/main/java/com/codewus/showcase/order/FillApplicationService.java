package com.codewus.showcase.order;

import com.codewus.showcase.order.model.FillRecord;
import com.codewus.showcase.persistence.mapper.ApplicationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FillApplicationService {
    private final ApplicationMapper mapper;
    private final TransactionTemplate transaction;

    public FillApplicationService(ApplicationMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean apply(FillRecord fill) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            // Serialize both existing and first-time state creation before checking the ledger.
            mapper.ensureState(fill);
            mapper.lockState(fill);
            if (mapper.isApplied(fill.id())) return false;
            mapper.updateState(fill);
            mapper.markApplied(fill.id());
            return true;
        }));
    }
}
