package com.hospital.core.clinical.application;

import org.apache.ibatis.builder.MapperBuilderAssistant;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

/**
 * 纯 Mockito 单测(不启 Spring 上下文)的 MyBatis-Plus 支撑工具。
 *
 * <p>R-05 把 {@code pay()} / {@code finishVisit(force=true)} 的逐条 {@code updateById}
 * 改成了 {@code LambdaUpdateWrapper} 批量 UPDATE。MP 的 {@code set(SFunction, value)}
 * 会<b>即时</b>解析 lambda 对应的列名,依赖 MP 的 lambda 缓存(TableInfo);
 * 该缓存在 Spring 上下文启动时由 mapper 注册流程自动初始化,而纯单测没有上下文,
 * 直接调用会抛 {@code MybatisPlusException: can not find lambda cache for this entity}。
 *
 * <p>因此在单测里手动初始化一次实体元数据(仅测试环境需要,生产不受影响)。</p>
 */
final class MybatisPlusTestSupport {

    private MybatisPlusTestSupport() {
    }

    /** 为指定实体初始化 MP 的 TableInfo / lambda 缓存(幂等可重复调用)。 */
    static void initLambdaCache(Class<?>... entities) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : entities) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}
