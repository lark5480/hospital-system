package com.hospital.core.iam;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 菜单注册表 = 菜单唯一事实源(静态配置)。
 * 系统按当前用户 authorities 递归裁剪,无登录主体/匿名时返回完整菜单(兼容本地零摩擦)。
 * 这是菜单级权限的最小可用形态:结构外置(YAML/DB)留作后续,当前静态配置已满足学习/作品集需要且可逆。
 */
@Component
public class MenuConfig {

    /** 五权:visit:entry / visit:audit / order:execute / charge:pay / system:admin */
    private static final List<String> VISIT = List.of("visit:entry");
    private static final List<String> AUDIT = List.of("visit:audit");
    private static final List<String> EXECUTE = List.of("order:execute");
    private static final List<String> PHARMACY_DISPENSE = List.of("pharmacy:dispense");
    private static final List<String> PAY = List.of("charge:pay");
    private static final List<String> ADMIN = List.of("system:admin");
    private static final List<String> CASHIER = List.of("charge:pay", "system:admin");
    
    public List<MenuItem> registry() {
        return List.of(
                new MenuItem("dashboard", "工作台", "/dashboard", "HomeFilled", List.of(), List.of()),
                new MenuItem("visits", "门诊就诊", "/visits", "FirstAidKit",
                        VISIT, List.of()),
                new MenuItem("patients", "患者管理", "/patients", "User", VISIT, List.of()),
                new MenuItem("notifications", "消息通知", "/notifications", "Bell", VISIT, List.of()),
                new MenuItem("pharmacy", "药事管理", null, "Box", PHARMACY_DISPENSE, List.of(
                        new MenuItem("pharmacy-prescriptions", "处方发药", "/pharmacy/prescriptions",
                                "List", PHARMACY_DISPENSE, List.of())
                )),
                new MenuItem("lab", "医技管理", null, "DataBoard", EXECUTE, List.of(
                        new MenuItem("lab-requisitions", "检验申请", "/lab/requisitions",
                                "List", EXECUTE, List.of()),
                        new MenuItem("exams", "检查执行", "/exams",
                                "VideoCamera", EXECUTE, List.of())
                )),
                new MenuItem("reports", "报告管理", "/reports", "Reading", VISIT, List.of()),
                new MenuItem("dispatch", "排队看板", "/dispatch", "Monitor",
                        VISIT, List.of()),
                new MenuItem("cashier", "收费管理", "/cashier", "Coin", PAY, List.of()),
                new MenuItem("org", "组织架构", null, "OfficeBuilding", ADMIN, List.of(
                        new MenuItem("org-departments", "科室管理", "/org/departments",
                                "List", ADMIN, List.of()),
                        new MenuItem("org-staff", "员工管理", "/org/staff",
                                "UserFilled", ADMIN, List.of()),
                        new MenuItem("org-roles", "角色权限", "/org/roles",
                                "Lock", ADMIN, List.of()),
                        new MenuItem("menu-manage", "菜单管理", "/menu-manage",
                                "Menu", ADMIN, List.of())
                )),
                new MenuItem("files", "文件管理", "/files", "Folder", ADMIN, List.of()),
                new MenuItem("patient", "体检预约(C端)", null, "Calendar", List.of("patient:booking"), List.of(
                        new MenuItem("patient-booking", "套餐预约", "/patient/booking", "Tickets",
                                List.of("patient:booking"), List.of()),
                        new MenuItem("patient-appointments", "我的预约", "/patient/appointments", "Calendar",
                                List.of("patient:booking"), List.of()),
                        new MenuItem("patient-myqueue", "我的排队", "/patient/my-queue", "List",
                                List.of("patient:booking"), List.of()),
                        new MenuItem("patient-my-reports", "我的报告", "/patient/my-reports", "Reading",
                                List.of("patient:booking"), List.of())
                ))
        );
    }
}