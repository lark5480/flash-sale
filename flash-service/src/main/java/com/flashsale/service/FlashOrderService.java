package com.flashsale.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.model.entity.FlashOrder;
import com.flashsale.model.vo.FlashOrderVO;

import java.util.List;

public interface FlashOrderService {

    FlashOrder createOrder(FlashOrder order);

    FlashOrder getOrderById(Long id);

    /** 用户查询自己的订单，非本人订单抛 FORBIDDEN */
    FlashOrder getOrderById(Long id, Long userId);

    /**
     * 分页查询用户的订单（带商品名称，支持状态与关键词筛选）
     *
     * @param status  订单状态，null 表示全部
     * @param keyword 关键词（订单号 / 商品名称），null 或空白表示不筛选
     */
    IPage<FlashOrderVO> listOrdersByUser(Long userId, long page, long size, Integer status, String keyword);

    IPage<FlashOrder> listAllOrders(long page, long size);

    long countByUserAndFlashSale(Long userId, Long flashSaleId);

    FlashOrderVO purchase(Long flashSaleId, Long userId);

    /** 查询订单处理状态（基于 MQ 消息幂等键） */
    String getOrderStatus(String messageKey);

    /** 查询超时未支付的待支付订单 */
    List<FlashOrder> getExpiredPendingOrders(int timeoutMinutes);

    /** 取消单个订单并归还库存 */
    void cancelOrderAndRestoreStock(FlashOrder order);

    /** 手动支付订单（测试用） */
    void payOrder(Long orderId);

    /** 用户支付自己的订单 */
    void payOrder(Long orderId, Long userId);

    /** 用户取消自己的订单并归还库存 */
    void cancelOrder(Long orderId, Long userId);

    /** 管理端退款并归还库存 */
    void refundOrder(Long orderId);

    /** 用户退款自己的已支付订单并归还库存 */
    void refundOrder(Long orderId, Long userId);

    /** 管理端删除已取消订单 */
    void deleteOrder(Long orderId);

    /** 用户删除自己的已取消订单 */
    void deleteOrder(Long orderId, Long userId);

    /**
     * 事务性扣减库存 + 创建订单（供 MQ Consumer 调用）
     * <p>
     * deductStock 和 INSERT 在同一事务内，要么同时成功，要么同时回滚。
     * messageKey 的 UNIQUE 索引提供 DB 级幂等兜底。
     *
     * @return 创建的订单，如果 messageKey 已存在则返回 null
     */
    FlashOrder deductStockAndCreateOrder(Long flashSaleId, FlashOrder order);
}
