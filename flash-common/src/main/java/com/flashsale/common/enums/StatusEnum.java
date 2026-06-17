package com.flashsale.common.enums;

/**
 * 通用启用/禁用状态枚举.
 * <p>
 * 用于用户、商品等实体的 status 字段，表示逻辑启用或禁用.
 * 与逻辑删除（is_deleted）不同：禁用后记录仍存在但不可使用，逻辑删除后记录不可见.
 */
public enum StatusEnum {

    /** 启用 */
    ENABLED(1, "enabled"),

    /** 禁用 */
    DISABLED(0, "disabled");

    /** 数据库存储值 */
    private final int code;
    /** 状态描述 */
    private final String desc;

    StatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
