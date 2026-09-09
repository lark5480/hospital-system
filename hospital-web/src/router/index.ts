import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '@/layouts/MainLayout.vue'
import DashboardView from '@/views/DashboardView.vue'
import VisitListView from '@/views/VisitListView.vue'
import VisitDetailView from '@/views/VisitDetailView.vue'
import NotificationView from '@/views/NotificationView.vue'
import FileView from '@/views/FileView.vue'
import PatientBookingView from '@/views/PatientBookingView.vue'
import PatientAppointmentsView from '@/views/PatientAppointmentsView.vue'
import PatientMyQueueView from '@/views/PatientMyQueueView.vue'
import PatientMyReportsView from '@/views/PatientMyReportsView.vue'
import PatientsView from '@/views/PatientsView.vue'
import DispatchView from '@/views/DispatchView.vue'
import ScreenView from '@/views/ScreenView.vue'
import PharmacyPrescriptionsView from '@/views/PharmacyPrescriptionsView.vue'
import PharmacyPrescriptionDetailView from '@/views/PharmacyPrescriptionDetailView.vue'
import LabRequisitionsView from '@/views/LabRequisitionsView.vue'
import LabRequisitionDetailView from '@/views/LabRequisitionDetailView.vue'
import ExamTasksView from '@/views/ExamTasksView.vue'
import ReportsView from '@/views/ReportsView.vue'
import RoleAuthView from '@/views/RoleAuthView.vue'
import LoginView from '@/views/LoginView.vue'
import ChangePasswordView from '@/views/ChangePasswordView.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * R-10: 强制改密放行白名单。
 * 已登录但仍在用系统默认口令时,除登录页与改密页外的所有路由都重定向到改密页。
 */
const PASSWORD_CHANGE_PATH = '/change-password'
const MUST_CHANGE_WHITELIST = ['/login', PASSWORD_CHANGE_PATH]

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { requiresAuth: false } },
    // R-10: 强制改密页(独立全屏页,不进 MainLayout,因此不产生标签页)
    {
      path: PASSWORD_CHANGE_PATH,
      name: 'change-password',
      component: ChangePasswordView,
      meta: { requiresAuth: true }
    },
    {
      path: '/',
      component: MainLayout,
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', name: 'dashboard', component: DashboardView, meta: { title: '工作台' } },
        { path: 'visits', name: 'visits', component: VisitListView, meta: { title: '门诊就诊' } },
        { path: 'visits/:id', name: 'visit-detail', component: VisitDetailView, meta: { title: '就诊详情' } },
        { path: 'registration', name: 'registration', component: () => import('@/views/RegistrationView.vue'), meta: { title: '门诊挂号' } },
        { path: 'registration/screen', name: 'outpatient-screen', component: () => import('@/views/OutpatientScreenView.vue'), meta: { title: '门诊大屏' } },
        { path: 'notifications', name: 'notifications', component: NotificationView, meta: { title: '消息通知' } },
        { path: 'files', name: 'files', component: FileView, meta: { title: '文件管理' } },
        { path: 'patient/booking', name: 'patient-booking', component: PatientBookingView, meta: { title: '套餐预约' } },
        { path: 'patient/registration', name: 'patient-registration', component: () => import('@/views/PatientRegistrationView.vue'), meta: { title: '自助挂号' } },
        { path: 'patient/appointments', name: 'patient-appointments', component: PatientAppointmentsView, meta: { title: '我的预约' } },
        { path: 'patient/my-queue', name: 'patient-myqueue', component: PatientMyQueueView, meta: { title: '我的排队' } },
        { path: 'patients', name: 'patients', component: PatientsView, meta: { title: '患者管理' } },
        { path: 'patient/my-reports', name: 'patient-my-reports', component: PatientMyReportsView, meta: { title: '我的报告' } },
        { path: 'dispatch', name: 'dispatch', component: DispatchView, meta: { title: '排队看板' } },
        { path: 'dispatch/screen', name: 'dispatch-screen', component: ScreenView, meta: { title: '科室大屏' } },
        { path: 'pharmacy/prescriptions', name: 'pharmacy-prescriptions', component: PharmacyPrescriptionsView, meta: { title: '处方发药' } },
        { path: 'pharmacy/prescriptions/:id', name: 'pharmacy-prescription-detail', component: PharmacyPrescriptionDetailView, meta: { title: '处方详情' } },
        { path: 'lab/requisitions', name: 'lab-requisitions', component: LabRequisitionsView, meta: { title: '检验申请' } },
        { path: 'lab/requisitions/:id', name: 'lab-requisition-detail', component: LabRequisitionDetailView, meta: { title: '检验详情' } },
        { path: 'exams', name: 'exams', component: ExamTasksView, meta: { title: '检查执行' } },
        { path: 'reports', name: 'reports', component: ReportsView, meta: { title: '报告管理' } },
        { path: 'org/departments', name: 'departments', component: () => import('@/views/DepartmentsView.vue'), meta: { title: '科室管理' } },
        { path: 'org/staff', name: 'staff', component: () => import('@/views/StaffView.vue'), meta: { title: '员工管理' } },
        { path: 'org/roles', name: 'roles', component: RoleAuthView, meta: { title: '角色权限' } },
        { path: 'menu-manage', name: 'MenuManage', component: () => import('@/views/MenuManageView.vue'), meta: { title: '菜单管理', requiresAuth: true } },
        { path: 'cashier', name: 'cashier', component: () => import('@/views/CashierView.vue'), meta: { title: '收费管理' } },
        { path: 'audit-logs', name: 'audit-logs', component: () => import('@/views/AuditLogView.vue'), meta: { title: '操作审计' } }
      ]
    },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView }
  ]
})

// 路由守卫:未登录跳登录页;已登录跳别再回登录页。
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth !== false && !auth.authenticated) {
    return '/login'
  }
  if (to.path === '/login' && auth.authenticated) {
    return '/dashboard'
  }
  // R-10: 仍在用系统默认口令 → 除登录/改密页外一律拦到改密页
  if (auth.authenticated && auth.mustChangePassword && !MUST_CHANGE_WHITELIST.includes(to.path)) {
    return PASSWORD_CHANGE_PATH
  }
  return true
})

export default router
