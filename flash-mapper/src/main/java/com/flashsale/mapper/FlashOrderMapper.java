package com.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashsale.model.entity.FlashOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FlashOrderMapper extends BaseMapper<FlashOrder> {

    @Select("SELECT * FROM flash_order WHERE message_key = #{messageKey} AND is_deleted = 0 LIMIT 1")
    FlashOrder selectByMessageKey(@Param("messageKey") String messageKey);

    /* 使用雪花ID插入订单（绕过 MyBatis-Plus IdType.AUTO 限制） */
    @Insert("INSERT INTO flash_order (id, user_id, item_id, flash_sale_id, flash_price, message_key, status) VALUES (#{id}, #{userId}, #{itemId}, #{flashSaleId}, #{flashPrice}, #{messageKey}, #{status})")
    int insertWithId(FlashOrder order);
}