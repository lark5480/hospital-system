package com.hospital.core.dispatch.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hospital.core.dispatch.domain.ExamTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExamTaskMapper extends BaseMapper<ExamTask> {

    /**
     * R-38: 一条 SQL 取出该 station 下一个可叫号候选,替代原"取全部 PENDING 再逐个回查"的 N+1
     * (单 station 积压 200 人时约 401 次 SQL → 1 次)。两条排队护栏在 SQL 内一次表达:
     * <ul>
     *   <li>{@code NOT EXISTS}:排除"已在其它科室检查中"的患者(患者级单活跃,一次只在一个科室);</li>
     *   <li>{@code seq = 该患者待检项的最小 seq}:仅当这是患者最早待检项时才可选(医生指定顺序)。</li>
     * </ul>
     * 最终按 seq 升序取最早的一条;无合规候选返回 null。
     */
    @Select("""
            SELECT t.* FROM dispatch.exam_task t
            WHERE t.station = #{station}
              AND t.status = 'PENDING'
              AND NOT EXISTS (
                    SELECT 1 FROM dispatch.exam_task x
                    WHERE x.patient_id = t.patient_id AND x.status = 'IN_PROGRESS')
              AND t.seq = (
                    SELECT MIN(p.seq) FROM dispatch.exam_task p
                    WHERE p.patient_id = t.patient_id AND p.status = 'PENDING')
            ORDER BY t.seq
            LIMIT 1
            """)
    ExamTask selectNextCandidate(String station);

    /**
     * R-38: 取该 station 当前最大排队序号(无任务时返回 0)。
     * 替代原来"selectList 拉整个 station 再 stream().max()"的全量加载,仅走单条聚合聚合查询。
     */
    @Select("SELECT COALESCE(MAX(seq), 0) FROM dispatch.exam_task WHERE station = #{station}")
    Integer selectMaxSeq(String station);
}
