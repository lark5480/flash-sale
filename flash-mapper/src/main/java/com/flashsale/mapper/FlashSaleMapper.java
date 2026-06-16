package com.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashsale.model.entity.FlashSale;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FlashSaleMapper extends BaseMapper<FlashSale> {

    @Update("UPDATE flash_sale SET stock = stock - 1 WHERE id = #{id} AND stock > 0 AND status = 1 AND is_deleted = 0")
    int deductStock(@Param("id") Long id);

    @Update("UPDATE flash_sale SET stock = stock + #{count} WHERE id = #{id} AND is_deleted = 0")
    int restoreStock(@Param("id") Long id, @Param("count") int count);
}
