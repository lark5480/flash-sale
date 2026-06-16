package com.flashsale.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.model.entity.FlashOrder;
import com.flashsale.model.vo.FlashOrderVO;

import java.util.List;

public interface FlashOrderService {

    FlashOrder createOrder(FlashOrder order);

    FlashOrder getOrderById(Long id);

    IPage<FlashOrder> listOrdersByUser(Long userId, long page, long size);

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
}
