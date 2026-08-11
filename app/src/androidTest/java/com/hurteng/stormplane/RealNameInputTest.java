package com.hurteng.stormplane;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 真机 UI 自动化辅助：自包含地走完「启动沙漠风暴 → 关公告 → 右上角登录 →
 * 快速注册 → 防沉迷实名认证提交」链路，用于验证自研 SDK 的实名接口。
 *
 * <p>跑 instrumentation 会重启 target 应用进程，因此测试必须自己拉起宿主、
 * 处理公告与登录，再操作实名页，不能假设页面已就绪。</p>
 *
 * <p>中文姓名通过 UiAutomation 的 ACTION_SET_TEXT 注入（绕过 IME 的 ASCII 限制），
 * 实名信息由 instrumentation 参数传入，代码内不保留敏感信息：</p>
 * <pre>
 *   adb shell am instrument -w \
 *       -e realName 姓名 -e idCard 身份证号 \
 *       com.hurteng.stormplane.test/androidx.test.runner.AndroidJUnitRunner \
 *       -e class com.hurteng.stormplane.RealNameInputTest#quickRegisterAndRealName
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public final class RealNameInputTest {
    /** 节点等待超时（毫秒）。 */
    private static final long WAIT_MS = 45_000L;
    /** 整条导航超时（毫秒）。 */
    private static final long NAVIGATE_MS = 120_000L;

    @Test public void quickRegisterAndRealName() {
        Bundle args = InstrumentationRegistry.getArguments();
        String name = args.getString("realName");
        String idCard = args.getString("idCard");
        // 未传实名参数时跳过（JUnit Assume），避免其它测试运行时连带失败。
        org.junit.Assume.assumeTrue(name != null && !name.isEmpty()
                && idCard != null && !idCard.isEmpty());
        launchHost();
        // 导航：关公告 → 点右上角「登录」→ 切「快速注册」→ 填密码 → 勾协议 → 立即注册 → 实名页。
        long deadline = SystemClock.uptimeMillis() + NAVIGATE_MS;
        boolean loginClicked = false;
        boolean regTabClicked = false;
        boolean passwordFilled = false;
        while (SystemClock.uptimeMillis() < deadline) {
            if (findNode("请输入真实姓名") != null) break;          // 已在实名页
            AccessibilityNodeInfo confirm = findNode("确认");       // 公告弹窗
            if (confirm != null) {
                clickNode(confirm);
                SystemClock.sleep(1_500L);
                continue;
            }
            AccessibilityNodeInfo noSave = findNode("永不保存");    // 系统保存密码弹窗
            if (noSave != null) {
                clickNode(noSave);
                SystemClock.sleep(1_000L);
                continue;
            }
            if (!loginClicked) {
                AccessibilityNodeInfo loginBtn = findNode("登录");  // 游戏页右上角登录按钮
                if (loginBtn != null) {
                    clickNode(loginBtn);
                    loginClicked = true;
                    SystemClock.sleep(2_000L);
                    continue;
                }
            }
            if (!regTabClicked) {
                AccessibilityNodeInfo regTab = findNode("快速注册"); // 登录页快速注册 tab
                if (regTab != null) {
                    clickNode(regTab);
                    regTabClicked = true;
                    SystemClock.sleep(1_000L);
                    continue;
                }
            }
            if (regTabClicked && !passwordFilled) {
                AccessibilityNodeInfo pwd = findNode("请输入密码"); // 快速注册密码框
                if (pwd != null) {
                    setText(pwd, "test123456");
                    passwordFilled = true;
                    SystemClock.sleep(800L);
                    continue;
                }
            }
            if (passwordFilled) {
                AccessibilityNodeInfo checkbox = findCheckableUnchecked(); // 协议勾选框
                if (checkbox != null) {
                    clickNode(checkbox);
                    SystemClock.sleep(500L);
                    continue;
                }
                AccessibilityNodeInfo regBtn = findNode("立即注册"); // 提交注册
                if (regBtn != null) {
                    clickNode(regBtn);
                    SystemClock.sleep(2_000L);
                    continue;
                }
            }
            SystemClock.sleep(1_000L);
        }
        // 到实名页后填写并提交（注册成功后自动进入）。
        AccessibilityNodeInfo nameField = waitForNode("请输入真实姓名");
        if (nameField == null) throw new IllegalStateException("实名页未出现");
        setText(nameField, name);
        AccessibilityNodeInfo idField = waitForNode("请输入身份证号");
        if (idField == null) throw new IllegalStateException("未找到身份证号输入框");
        setText(idField, idCard);
        AccessibilityNodeInfo submit = waitForNode("立即认证");
        if (submit == null) throw new IllegalStateException("未找到「立即认证」按钮");
        clickNode(submit);
        SystemClock.sleep(6_000L);
    }

    /** 通过系统启动器拉起宿主 MainActivity。 */
    private void launchHost() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent == null) throw new IllegalStateException("找不到宿主启动 Intent");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        SystemClock.sleep(3_000L);
    }

    private void clickNode(AccessibilityNodeInfo node) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        SystemClock.sleep(500L);
    }

    /** 按文本查找节点；找不到则每 500ms 重试，直到超时。 */
    private AccessibilityNodeInfo waitForNode(String text) {
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo node = findNode(text);
            if (node != null) return node;
            SystemClock.sleep(500L);
        }
        return null;
    }

    private AccessibilityNodeInfo findNode(String text) {
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo found = findInTree(root, text);
        root.recycle();
        return found;
    }

    private AccessibilityNodeInfo findInTree(AccessibilityNodeInfo node, String text) {
        CharSequence nodeText = node.getText();
        CharSequence nodeHint = node.getHintText();
        CharSequence nodeDesc = node.getContentDescription();
        if ((nodeText != null && text.contentEquals(nodeText))
                || (nodeHint != null && text.contentEquals(nodeHint))
                || (nodeDesc != null && text.contentEquals(nodeDesc))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findInTree(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 用 UiAutomation ACTION_SET_TEXT 直接写入文本，绕过 IME 的 ASCII 限制（支持中文）。 */
    private void setText(AccessibilityNodeInfo node, String value) {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        SystemClock.sleep(800L);
    }

    /** 找到页面上第一个未勾选的 checkable 节点（快速注册页协议勾选框）。 */
    private AccessibilityNodeInfo findCheckableUnchecked() {
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo found = findCheckable(root);
        root.recycle();
        return found;
    }

    private AccessibilityNodeInfo findCheckable(AccessibilityNodeInfo node) {
        if (node.isCheckable() && !node.isChecked()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findCheckable(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
