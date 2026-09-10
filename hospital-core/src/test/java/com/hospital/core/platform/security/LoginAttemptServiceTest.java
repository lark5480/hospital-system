package com.hospital.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R-10 / R-12:登录失败锁定与计数累积的回归测试。
 *
 * <p><b>守护的缺陷</b>:{@link LoginAttemptService#isLocked(String)} 原先在"记录存在但未锁定"时
 * 也会把记录删掉。而 {@code AuthController} 在登录失败后会再调一次 {@code isLocked}
 * 判断是否刚触发锁定(失败 → 401 / 刚锁定 → 429),于是每次失败都把计数清零,
 * 连续失败永远攒不到 5 次,锁定功能<b>完全失效</b>。
 *
 * <p>{@code shouldLockAfterMaxFailures} 直接复现了这条调用链(每次失败后都查询一次锁定状态),
 * 是防止该缺陷回归的关键用例。
 */
class LoginAttemptServiceTest {

    private static final String PHONE = "13800000000";

    @Test
    @DisplayName("R-10 回归:连续失败 5 次后必须锁定(每次失败后都查一次 isLocked,模拟 Controller 调用链)")
    void shouldLockAfterMaxFailures() {
        LoginAttemptService service = new LoginAttemptService();

        // 前 4 次:未锁定。每次失败后都调用 isLocked —— 这正是原先会把计数清零的地方
        for (int i = 1; i <= 4; i++) {
            service.onFailure(PHONE);
            assertThat(service.isLocked(PHONE))
                    .as("第 %d 次失败后不应锁定", i)
                    .isFalse();
        }

        // 第 5 次失败触发锁定
        service.onFailure(PHONE);
        assertThat(service.isLocked(PHONE))
                .as("第 5 次失败后应处于锁定状态")
                .isTrue();
    }

    @Test
    @DisplayName("R-10:锁定期间 isLocked 持续为 true(不会被后续查询清除)")
    void shouldStayLockedWhileWithinLockWindow() {
        LoginAttemptService service = new LoginAttemptService();
        for (int i = 0; i < 5; i++) {
            service.onFailure(PHONE);
        }

        // 连续查询多次都必须保持锁定 —— 若查询有清除副作用,第二次就会变成 false
        for (int i = 0; i < 5; i++) {
            assertThat(service.isLocked(PHONE))
                    .as("锁定期内第 %d 次查询仍应为锁定", i + 1)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("R-10:登录成功清零失败计数,重新累计 5 次才会再次锁定")
    void shouldResetCounterAfterSuccess() {
        LoginAttemptService service = new LoginAttemptService();

        for (int i = 0; i < 4; i++) {
            service.onFailure(PHONE);
        }
        service.onSuccess(PHONE);

        // 清零后需重新累计 4 次才不锁定
        for (int i = 1; i <= 4; i++) {
            service.onFailure(PHONE);
            assertThat(service.isLocked(PHONE))
                    .as("清零后第 %d 次失败不应锁定", i)
                    .isFalse();
        }
        service.onFailure(PHONE);
        assertThat(service.isLocked(PHONE)).as("重新累计 5 次后应锁定").isTrue();
    }

    @Test
    @DisplayName("R-12:手机号为空不计数,且 isLocked 恒为 false(避免误锁)")
    void blankPhoneShouldNeverLock() {
        LoginAttemptService service = new LoginAttemptService();

        for (int i = 0; i < 10; i++) {
            service.onFailure(null);
            service.onFailure("   ");
        }

        assertThat(service.isLocked(null)).isFalse();
        assertThat(service.isLocked("   ")).isFalse();
        assertThat(service.isLocked(PHONE)).isFalse();
    }

    @Test
    @DisplayName("R-12:用户不存在场景只计 IP,不按手机号计数(否则撞库可锁死他人账号)")
    void unknownUserShouldOnlyCountIp() {
        LoginAttemptService service = new LoginAttemptService();

        for (int i = 0; i < 10; i++) {
            service.onIpFailure("192.168.1.100");
        }

        assertThat(service.isLocked(PHONE))
                .as("仅 IP 失败不得锁定任何手机号")
                .isFalse();
        assertThat(service.isIpBlocked("192.168.1.100"))
                .as("IP 窗口内 10 次未达 30 次阈值,不应限流")
                .isFalse();
    }
}
