package com.qar.securitysystem.dto;

import com.qar.securitysystem.model.AccountRequestEntity;

public class RegistrationResult {
    private AccountRequestEntity entity;

    public RegistrationResult(AccountRequestEntity entity) {
        this.entity = entity;
    }

    public AccountRequestEntity getEntity() {
        return entity;
    }
}
