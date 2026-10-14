package com.bugeon.fintechledger.audit.service;

import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.domain.AuditLog;
import com.bugeon.fintechledger.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 감사 로그 서비스.
 *
 * - 성공 기록(REQUIRED): 비즈니스 트랜잭션에 참여한다. 돈의 이동과 감사 기록이 함께 커밋되거나
 *   함께 롤백되므로 "감사 없는 이동"도, "일어나지 않은 일의 성공 감사"도 생기지 않는다.
 * - 실패 기록(REQUIRES_NEW): 비즈니스 트랜잭션이 롤백되어도 독립적으로 커밋된다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Transactional
    public void logSuccess(AuditAction action,
                           UUID actorId, String actorEmail,
                           String targetType, UUID targetId,
                           String contextJson) {
        auditLogRepository.save(AuditLog.success(action, actorId, actorEmail,
                targetType, targetId, contextJson, null, null));
        log.debug("Audit SUCCESS: action={} actor={} target={}/{}", action, actorId, targetType, targetId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailure(AuditAction action,
                           UUID actorId, String actorEmail,
                           String targetType, UUID targetId,
                           String contextJson) {
        auditLogRepository.save(AuditLog.failure(action, actorId, actorEmail,
                targetType, targetId, contextJson, null, null));
        log.debug("Audit FAILURE: action={} actor={}", action, actorId);
    }
}
