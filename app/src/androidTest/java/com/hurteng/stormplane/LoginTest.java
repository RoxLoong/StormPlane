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
 * 账号密码登录」链路，用于验证自研 SDK 登录。账号密码由 instrumentation 参数传入：
 * <pre>
 *   adb shell am instrument -w \
 *       -e account 账号 -e password 密码 \
 *       com.hurteng.stormplane.test/androidx.test.runner.AndroidJUnitRunner \
 *       -e class com.hurteng.stormplane.LoginTest#accountLogin
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public final class LoginTest {
    private static final long NAVIGATE_MS = 300_000L;

    @Test public void accountLogin() {
        Bundle args = InstrumentationRegistry.getArguments();
        String account = args.getString("account");
        String password = args.getString("password");
        if (account == null || account.isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalStateException("缺少 -e account / -e password 实参");
        }
        launchHost();
        long deadline = SystemClock.uptimeMillis() + NAVIGATE_MS;
        boolean loginClicked = false;
        boolean accountTabClicked = false;
        boolean formFilled = false;
        while (SystemClock.uptimeMillis() < deadline) {
            // 登录成功：SdkActivity 关闭回到游戏页，右上角按钮不再是「登录」，
            // 且页面无登录表单（区别于登录页顶部的「登录」tab）。
            // 成功后不退出，保持宿主前台，供外部 adb 操作继续验证角色上报/支付链路。
            if (findNode("登录") == null && findNode("快速注册") == null && findNode("进入游戏") == null) {
                SystemClock.sleep(1_000L);
                continue;
            }
            AccessibilityNodeInfo confirm = findNode("确认");       // 公告弹窗
            if (confirm != null) {
                clickNode(confirm);
                SystemClock.sleep(1_500L);
                continue;
            }
            if (!loginClicked) {
                AccessibilityNodeInfo loginBtn = findNode("登录");  // 游戏页右上角登录按钮
                if (loginBtn != null && findNode("请输入您的账号") == null) {
                    clickNode(loginBtn);
                    loginClicked = true;
                    SystemClock.sleep(2_000L);
                    continue;
                }
            }
            if (loginClicked && !accountTabClicked) {
                AccessibilityNodeInfo tab = findNode("账号登录");   // 登录页底部账号登录 tab
                if (tab != null) {
                    clickNode(tab);
                    accountTabClicked = true;
                    SystemClock.sleep(1_000L);
                    continue;
                }
            }
            if (accountTabClicked && !formFilled) {
                AccessibilityNodeInfo acc = findNode("请输入您的账号");
                AccessibilityNodeInfo pwd = findNode("请输入密码");
                if (acc != null && pwd != null) {
                    setText(acc, account);
                    setText(pwd, password);
                    formFilled = true;
                    SystemClock.sleep(800L);
                    continue;
                }
            }
            if (formFilled) {
                AccessibilityNodeInfo checkbox = findCheckableUnchecked(); // 协议勾选框
                if (checkbox != null) {
                    clickNode(checkbox);
                    SystemClock.sleep(500L);
                    continue;
                }
                AccessibilityNodeInfo enter = findNode("进入游戏");
                if (enter != null) {
                    clickNode(enter);
                    SystemClock.sleep(3_000L);
                    continue;
                }
            }
            SystemClock.sleep(1_000L);
        }
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

    private void setText(AccessibilityNodeInfo node, String value) {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        SystemClock.sleep(800L);
    }

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
