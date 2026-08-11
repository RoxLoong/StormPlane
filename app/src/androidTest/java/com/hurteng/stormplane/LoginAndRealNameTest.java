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
 * 真机 UI 自动化辅助：账号登录 + 防沉迷实名认证（为已注册未实名账号补齐实名）。
 * 实名信息与登录凭据由 instrumentation 参数传入，代码内不保留敏感信息：
 * <pre>
 *   adb shell am instrument -w \
 *       -e account 账号 -e password 密码 -e realName 姓名 -e idCard 身份证号 \
 *       com.hurteng.stormplane.test/androidx.test.runner.AndroidJUnitRunner \
 *       -e class com.hurteng.stormplane.LoginAndRealNameTest#loginAndRealName
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public final class LoginAndRealNameTest {
    private static final long NAVIGATE_MS = 180_000L;

    @Test public void loginAndRealName() {
        Bundle args = InstrumentationRegistry.getArguments();
        String account = args.getString("account");
        String password = args.getString("password");
        String name = args.getString("realName");
        String idCard = args.getString("idCard");
        org.junit.Assume.assumeTrue(account != null && !account.isEmpty()
                && password != null && !password.isEmpty()
                && name != null && !name.isEmpty() && idCard != null && !idCard.isEmpty());
        launchHost();
        long deadline = SystemClock.uptimeMillis() + NAVIGATE_MS;
        boolean loginClicked = false;
        boolean accountTabClicked = false;
        boolean formFilled = false;
        while (SystemClock.uptimeMillis() < deadline) {
            if (findNode("请输入真实姓名") != null) break;              // 已到实名页
            AccessibilityNodeInfo confirm = findNode("确认");           // 公告弹窗
            if (confirm != null) {
                clickNode(confirm);
                SystemClock.sleep(1_500L);
                continue;
            }
            if (!loginClicked) {
                AccessibilityNodeInfo loginBtn = findNode("登录");      // 游戏页右上角登录按钮
                if (loginBtn != null && findNode("请输入您的账号") == null) {
                    clickNode(loginBtn);
                    loginClicked = true;
                    SystemClock.sleep(2_000L);
                    continue;
                }
            }
            if (loginClicked && !accountTabClicked) {
                AccessibilityNodeInfo tab = findNode("账号登录");       // 登录页底部账号登录 tab
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
                    SystemClock.sleep(2_500L);
                    continue;
                }
            }
            SystemClock.sleep(1_000L);
        }
        // 实名页填写并提交。
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

    private AccessibilityNodeInfo waitForNode(String text) {
        long deadline = SystemClock.uptimeMillis() + 45_000L;
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
