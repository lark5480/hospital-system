/** 导航菜单节点(与后端 MenuItem 对应)。 */
export interface MenuItem {
  key: string
  title: string
  path: string | null
  icon: string
  authorities: string[]
  children?: MenuItem[]
}
