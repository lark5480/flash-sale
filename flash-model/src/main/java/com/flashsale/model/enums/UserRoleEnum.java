package com.flashsale.model.enums;

public enum UserRoleEnum {

    USER("USER"),
    ADMIN("ADMIN");

    private final String role;

    UserRoleEnum(String role) {
        this.role = role;
    }

    public String getRole() {
        return role;
    }
}
