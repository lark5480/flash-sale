package com.flashsale.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashsale.model.entity.FlashSale;
import com.flashsale.model.vo.FlashSaleVO;

import java.util.List;

public interface FlashSaleService {

    FlashSale createFlashSale(FlashSale flashSale);

    FlashSale updateFlashSale(FlashSale flashSale);

    void deleteFlashSale(Long id);

    FlashSale getFlashSaleById(Long id);

    List<FlashSaleVO> getActiveFlashSales();

    IPage<FlashSale> listFlashSales(long page, long size, Integer status);

    void updateStatus(Long id, Integer status);

    FlashSaleVO getDetailWithItem(Long id);

    /** 查询已到开始时间但尚未激活的秒杀活动 */
    List<FlashSale> getPendingSalesReadyToStart();

    /** 查询已过结束时间但尚未结束的秒杀活动 */
    List<FlashSale> getActiveSalesReadyToEnd();
}
