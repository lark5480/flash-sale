package com.flashsale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashsale.model.entity.FlashOrder;
import com.flashsale.model.vo.DashboardStatsVO;
import com.flashsale.model.vo.FlashOrderVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FlashOrderMapper extends BaseMapper<FlashOrder> {

    @Select("SELECT * FROM flash_order WHERE message_key = #{messageKey} AND is_deleted = 0 LIMIT 1")
    FlashOrder selectByMessageKey(@Param("messageKey") String messageKey);

    /* 使用雪花ID插入订单（绕过 MyBatis-Plus IdType.AUTO 限制） */
    @Insert("INSERT INTO flash_order (id, user_id, item_id, flash_sale_id, flash_price, message_key, status) VALUES (#{id}, #{userId}, #{itemId}, #{flashSaleId}, #{flashPrice}, #{messageKey}, #{status})")
    int insertWithId(FlashOrder order);

    /**
     * 统计用户在该场秒杀中仍占用限购额度的订单数。
     * <p>
     * 只有 2=已取消 与 3=已退款 会释放额度（与 Redis 限购计数的归还口径一致），
     * 其余状态（含未来新增的已发货等）一律继续占额度，因此用 NOT IN 而非白名单。
     */
    @Select("SELECT COUNT(1) FROM flash_order WHERE user_id = #{userId} AND flash_sale_id = #{flashSaleId}"
            + " AND status NOT IN (2, 3) AND is_deleted = 0")
    int countQuotaOccupied(@Param("flashSaleId") Long flashSaleId, @Param("userId") Long userId);

    /**
     * 分页查询某用户的订单，LEFT JOIN 商品表带出名称与图片，支持状态与关键词筛选。
     * <p>
     * 关键词同时匹配订单号与商品名称：订单号是雪花 ID，用户习惯粘贴后几位检索，
     * 因此用 LIKE 而非等值匹配；结果集已被 user_id 限定在单人范围内，全表扫描风险可控。
     *
     * @param page    分页参数，由 MyBatis-Plus 分页插件自动拦截改写
     * @param userId  用户 ID
     * @param status  订单状态，null 表示不筛选
     * @param keyword 关键词（订单号 / 商品名称），null 表示不筛选
     */
    @Select("<script>"
            + "SELECT o.id, o.user_id, o.item_id, o.flash_sale_id, o.flash_price, o.status, o.create_time, o.message_key,"
            + " i.name AS item_name, i.image AS item_image "
            + "FROM flash_order o LEFT JOIN item i ON o.item_id = i.id AND i.is_deleted = 0 "
            + "WHERE o.user_id = #{userId} AND o.is_deleted = 0 "
            + "<if test='status != null'>AND o.status = #{status} </if>"
            + "<if test='keyword != null'>AND (o.id LIKE CONCAT('%', #{keyword}, '%') OR i.name LIKE CONCAT('%', #{keyword}, '%')) </if>"
            + "ORDER BY o.create_time DESC"
            + "</script>")
    @ResultType(FlashOrderVO.class)
    IPage<FlashOrderVO> selectUserOrderPage(Page<?> page,
                                            @Param("userId") Long userId,
                                            @Param("status") Integer status,
                                            @Param("keyword") String keyword);

    /**
     * 近 N 日每日订单趋势（供控制台图表）：
     * 按自然日分组统计下单量、支付单数与已支付金额。
     *
     * @param start 起始日期零点（含），按天向前取 6 天即可得到近 7 日
     */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS date,"
            + " COUNT(1) AS orderCount,"
            + " COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) AS paidCount,"
            + " COALESCE(SUM(CASE WHEN status = 1 THEN flash_price ELSE 0 END), 0) AS paidAmount"
            + " FROM flash_order"
            + " WHERE is_deleted = 0 AND create_time >= #{start}"
            + " GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')"
            + " ORDER BY date")
    List<DashboardStatsVO.DailyOrderStat> selectDailyTrend(@Param("start") LocalDateTime start);

    /**
     * 今日订单汇总：总下单数、已支付单数、已支付金额（无订单时各列返回 0）。
     */
    @Select("SELECT COUNT(1) AS orderCount,"
            + " COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0) AS paidCount,"
            + " COALESCE(SUM(CASE WHEN status = 1 THEN flash_price ELSE 0 END), 0) AS paidAmount"
            + " FROM flash_order"
            + " WHERE is_deleted = 0 AND create_time >= #{start}")
    DashboardStatsVO.DailyOrderStat selectTodaySummary(@Param("start") LocalDateTime start);

    /**
     * 订单状态分布（0=待支付/1=已支付/2=已取消/3=已退款），缺失的状态由 service 补零。
     */
    @Select("SELECT status, COUNT(1) AS count FROM flash_order WHERE is_deleted = 0 GROUP BY status")
    List<DashboardStatsVO.StatusCount> selectStatusDistribution();
}