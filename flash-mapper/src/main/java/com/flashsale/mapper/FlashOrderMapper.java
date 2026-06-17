package com.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashsale.model.entity.FlashOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FlashOrderMapper extends BaseMapper<FlashOrder> {

    @Select("SELECT * FROM flash_order WHERE message_key = #{messageKey} AND is_deleted = 0 LIMIT 1")
    FlashOrder selectByMessageKey(@Param("messageKey") String messageKey);
}
