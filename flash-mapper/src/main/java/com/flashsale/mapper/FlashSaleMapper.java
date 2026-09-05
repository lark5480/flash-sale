package com.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashsale.model.entity.FlashSale;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FlashSaleMapper extends BaseMapper<FlashSale> {

    /**
     * 扣减已成交库存台账。
     * <p>
     * 只由 MQ Consumer 在 Redis 已原子预扣、消息已入队之后调用，因此这里不再校验 status：
     * 定时任务把场次改成「已结束」最多滞后 1 分钟，若在此处卡 status=1，
     * 那批已完成预扣的订单会全部落空（用户侧显示成功/处理中却查不到订单）。
     * 是否还能卖由 Redis 库存键决定，{@code stock > 0} 是 DB 侧防硬超卖的最后一道闸门。
     */
    @Update("UPDATE flash_sale SET stock = stock - 1 WHERE id = #{id} AND stock > 0 AND is_deleted = 0")
    int deductStock(@Param("id") Long id);

    @Update("UPDATE flash_sale SET stock = stock + #{count} WHERE id = #{id} AND is_deleted = 0")
    int restoreStock(@Param("id") Long id, @Param("count") int count);
}
