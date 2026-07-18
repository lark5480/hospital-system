export interface Department {
  id: number
  name: string
  code: string
  description: string
  createdAt: string
}

export interface Staff {
  id: number
  name: string
  gender: string
  phone: string
  deptId: number
  position: string
  username: string
  status: string
  createdAt: string
}

export interface DepartmentForm {
  name: string
  code: string
  description: string
}

export interface StaffForm {
  name: string
  gender: string
  phone: string
  deptId: number
  position: string
  username: string
}
