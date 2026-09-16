package com.example.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckill.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 乐观关闭超时订单（文档 7.8）：仅当 status=0 时关闭，返回受影响行数。
     */
    @Update("UPDATE seckill_order SET status = 3 WHERE id = #{id} AND status = 0")
    int closeExpired(@Param("id") Long id);
}
