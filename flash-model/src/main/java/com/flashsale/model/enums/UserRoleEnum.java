package com.flashsale.model.enums;

/**
 * 用户角色枚举.
 * <p>
 * 存入数据库 role 字段，JWT 中作为 claim 携带，Spring Security 中映射为 {@code ROLE_USER} / {@code ROLE_ADMIN}.
 */
public enum UserRoleEnum {

    /** 普通用户，C 端登录 */
    USER("USER"),

    /** 管理员，B 端登录，可访问 /admin/** 路径 */
    ADMIN("ADMIN");

    private final String role;

    UserRoleEnum(String role) {
        this.role = role;
    }

    /**
     * @return 角色字符串，对应 JWT role claim 和 Spring Security authority
     */
    public String getRole() {
        return role;
    }
}
