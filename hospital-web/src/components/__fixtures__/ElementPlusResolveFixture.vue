<script setup lang="ts">
/**
 * R-39 回归测试夹具(仅供单测挂载,不属于业务页面)。
 *
 * 刻意放进一个真实 .vue 文件里 —— unplugin-vue-components 只处理 .vue 文件,
 * 若把 <el-button> 写成 .ts 里的字符串模板,插件不会介入,测试就失去意义。
 *
 * 这里覆盖两类"构建期无法发现"的按需引入问题:
 *  1) 组件标签 <el-button> 能否被解析成真实组件(否则渲染为未知元素);
 *  2) 指令 v-loading 能否被解析(组件标签走 resolver,指令不一定会)。
 *
 * loading 做成 prop 是为了让测试能翻转它,从而断言指令确实随数据变化而增删遮罩。
 */
withDefaults(defineProps<{ loading?: boolean }>(), { loading: true })
</script>

<template>
  <div class="fixture">
    <el-button class="fixture-btn" type="primary">按钮</el-button>
    <el-tag class="fixture-tag">标签</el-tag>
    <div v-loading="loading" class="fixture-loading">加载中容器</div>
  </div>
</template>
