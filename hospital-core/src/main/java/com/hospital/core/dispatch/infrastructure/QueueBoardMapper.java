package com.hospital.core.dispatch.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.dispatch.domain.QueueBoard;

@Mapper
public interface QueueBoardMapper extends BaseMapper<QueueBoard> {

    /**
     * R-37: 一条 {@code SELECT DISTINCT station} 取活跃工位,替代原实现
     * "select distinct station 拉全表 + Java distinct"。
     * 口径与看板 {@link com.hospital.core.dispatch.application.DispatchService#board} 保持一致:
     * 仅按状态过滤(剔除已完成的 DONE),<b>不加日期限制</b> —— 见 board() 的说明。
     */
    @Select("""
            SELECT DISTINCT station FROM dispatch.queue_board
            WHERE status IN ('PENDING', 'IN_PROGRESS', 'SKIPPED')
            """)
    List<String> selectActiveStations();
}
