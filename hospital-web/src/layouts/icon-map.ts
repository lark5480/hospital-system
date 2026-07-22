import type { Component } from 'vue'
import {
  HomeFilled, FirstAidKit, Bell, Folder, Calendar, Ticket, Tickets, Monitor,
  Box, List, DataBoard, Reading, UserFilled, OfficeBuilding, Coin, User,
  VideoCamera, Lock, Menu
} from '@element-plus/icons-vue'

/** 图标名 → Element Plus 图标组件的映射。后端只下发表征字符串,前端据此渲染。 */
export const iconMap: Record<string, Component> = {
  HomeFilled, FirstAidKit, Bell, Folder, Calendar, Ticket, Tickets, Monitor,
  Box, List, DataBoard, Reading, UserFilled, OfficeBuilding, Coin, User,
  VideoCamera, Lock, Menu
}
