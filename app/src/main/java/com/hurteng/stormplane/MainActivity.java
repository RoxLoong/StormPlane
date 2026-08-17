package com.hurteng.stormplane;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.bycw.sdk.BaiYouSdk;
import com.bycw.sdk.ChannelInfo;
import com.bycw.sdk.LoginResult;
import com.bycw.sdk.PayRequest;
import com.bycw.sdk.PayResult;
import com.bycw.sdk.RoleInfo;
import com.bycw.sdk.SdkConfig;
import com.bycw.sdk.SdkError;
import com.bycw.sdk.callback.InitCallback;
import com.bycw.sdk.callback.LoginCallback;
import com.bycw.sdk.callback.PayCallback;
import com.bycw.sdk.callback.ResultCallback;
import com.hurteng.stormplane.constant.ConstantUtil;
import com.hurteng.stormplane.constant.DebugConstant;
import com.hurteng.stormplane.myplane.BuildConfig;
import com.hurteng.stormplane.role.RoleProfile;
import com.hurteng.stormplane.role.RoleProfileStore;
import com.hurteng.stormplane.sounds.GameSoundPool;
import com.hurteng.stormplane.view.EndView;
import com.hurteng.stormplane.view.MainView;
import com.hurteng.stormplane.view.ReadyView;

import java.util.List;

/**
 * 沙漠风暴（打飞机）宿主：原版自绘 SurfaceView 游戏流程保持不变，
 * 叠加接入「百游盒子」自研 SDK 的真实游戏链路：
 * 初始化 → 登录 → 角色档案（准备界面可新建/切换多角色）→ 开局/局内升级/结算累计上报角色
 * → 结算页支付复活（1 元道具）。
 *
 * <p>角色数据由游戏真实事件驱动：建角/登录时间落盘、跨局分数累计推导等级与战力，
 * 上报时携带真实时间戳（后台校验，SDK 不替宿主生成）。
 */
public class MainActivity extends Activity {
    /** 宿主日志 TAG，便于 adb logcat 过滤。 */
    private static final String TAG = "StormPlane";

    private EndView endView;
    private MainView mainView;
    private ReadyView readyView;
    private GameSoundPool sounds;
    /** 游戏自绘 View 容器：游戏铺底，SDK/角色控件叠加在上层。 */
    private FrameLayout root;
    /** 结算页「支付复活」按钮。 */
    private Button reviveButton;
    /** 支付请求进行中标记，防止重复点击。 */
    private boolean paying;

    /** 角色档案存储（多角色，真实时间戳，跨局成长）。 */
    private RoleProfileStore roleStore;
    /** 准备界面顶部的角色卡片容器（含卡片文本 + 新建/切换按钮）。 */
    private LinearLayout roleCardPanel;
    /** 角色卡片文本：角色名 / 区服 · 等级 · 战力。 */
    private TextView roleCard;
    /** 角色卡片上的「新建角色」「切换角色」按钮。 */
    private Button newRoleButton;
    private Button switchRoleButton;
    /** 角色卡片底部的渠道信息行：custId / inviteCode（展示 SDK getChannelInfo）。 */
    private TextView channelLine;

    @SuppressLint("HandlerLeak")
    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == ConstantUtil.TO_MAIN_VIEW) {
                // 必须登录才能进入游戏：未登录先拉起 SDK 登录，成功后才开局。
                ensureLoginThenStart();
            } else if (msg.what == ConstantUtil.ROLE_LEVEL_UP) {
                // 局内升级：重新上报角色，带上当前局内等级/得分（真实玩法状态）。
                reportActiveRole("局内升级");
            } else if (msg.what == ConstantUtil.TO_END_VIEW) {
                toEndView(msg.arg1);
            } else if (msg.what == ConstantUtil.END_GAME) {
                endGame();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 全屏与无标题栏由 application theme（AppBaseTheme → Black.NoTitleBar.Fullscreen）声明，
        // SDK 页面不声明自身 theme，继承后同样保持全屏，避免运行时 setFlags 造成两边不一致。
        sounds = new GameSoundPool(this);
        sounds.initGameSound();

        // 容器：ReadyView 铺底，右上角不再放登录按钮（未登录进游戏时强制登录）。
        root = new FrameLayout(this);
        readyView = new ReadyView(this, sounds);
        root.addView(readyView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        roleStore = new RoleProfileStore(this);
        buildRoleCard();
        // 监听 SDK 账号切换：切小号后重新绑定该小号的角色并上报，不能沿用上一个小号的角色。
        registerAccountChangedListener();

        initSdk();
    }

    /**
     * 注册 SDK 账号变更监听。切换小号（同一平台账号内）时，当前游戏角色必须改用
     * 新小号自己的角色（没有则自动建档），否则上报归属校验会因 charId 不属于新小号而失败；
     * 平台账号切换/登出（本地会话已清理）时隐藏角色卡片，等待重新登录后再建档。
     */
    private void registerAccountChangedListener() {
        BaiYouSdk.getInstance().setAccountChangedListener(subAccountOnly -> {
            if (BaiYouSdk.getInstance().isLoggedIn()) {
                // 小号已切换：SDK 已完成新小号登录，宿主重建该小号角色身份并上报。
                ensureRoleProfile();
                reportActiveRole("小号切换");
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "小号已切换，已改用该小号角色上报", Toast.LENGTH_SHORT).show());
            } else {
                // 平台账号切换/登出/改密码强制重登（本地会话已清理）：复位角色卡并立即拉起登录页。
                runOnUiThread(() -> {
                    if (roleCard != null) roleCard.setText("未登录");
                });
                ensureLogin();
            }
        });
    }

    /** 初始化自研 SDK：隐私授权 → 开屏公告 → 完成；已有登录态则恢复悬浮球。 */
    private void initSdk() {
        SdkConfig config = new SdkConfig.Builder()
                .appId(BuildConfig.BYCW_APP_ID)
                .clientKey(BuildConfig.BYCW_CLIENT_KEY)
                .debug(true)
                .build();
        BaiYouSdk.getInstance().initialize(this, config, new InitCallback() {
            @Override public void onSuccess() {
                Log.i(TAG, "SDK 初始化完成");
                updateChannelLine();
                if (BaiYouSdk.getInstance().isLoggedIn()) {
                    onLoggedIn();
                } else {
                    // 未登录：进入游戏即自动弹出登录弹窗，不用等玩家点「开始游戏」。
                    ensureLogin();
                }
            }
            @Override public void onFailure(SdkError error) {
                Toast.makeText(MainActivity.this, "SDK初始化失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 登录成功后的统一处理：确保角色档案存在、刷新登录时间、恢复悬浮球、刷新角色卡片。 */
    private void onLoggedIn() {
        ensureRoleProfile();
        BaiYouSdk.getInstance().showFloating(this);
        updateRoleCard();
    }

    /**
     * 把当前角色绑定到登录的小号：该小号已建角色则选中它，没建过则保持无角色（不自动建角），
     * 由玩家在准备界面显式「新建角色」；同时刷新登录时间。
     */
    private void ensureRoleProfile() {
        LoginResult result = BaiYouSdk.getInstance().getLoginResult();
        if (result == null) return;
        String subId = result.getSubAccountId();
        if (subId == null || subId.length() == 0) return;
        RoleProfile active = roleStore.setActiveForSub(subId);
        roleStore.touchLogin();
        Log.i(TAG, "当前小号 " + result.getSubAccountName() + "(" + subId + ")"
                + (active != null ? " 角色=" + active.getRoleName() : " 未创建角色"));
        updateRoleCard();
    }

    /** 自动登录：初始化完成未登录时拉起登录弹窗；拒绝/失败直接退出游戏。 */
    private void ensureLogin() {
        if (BaiYouSdk.getInstance().isLoggedIn()) return;
        BaiYouSdk.getInstance().login(this, new LoginCallback() {
            @Override public void onSuccess(LoginResult result) {
                onLoggedIn();
                Toast.makeText(MainActivity.this, "登录成功", Toast.LENGTH_SHORT).show();
            }
            @Override public void onCancel() {
                // 拒绝登录不允许进入游戏：提示后直接退出宿主。
                Toast.makeText(MainActivity.this, "未登录无法进入游戏", Toast.LENGTH_SHORT).show();
                finish();
            }
            @Override public void onFailure(SdkError error) {
                // 登录失败同样无法进入游戏：提示后退出宿主。
                Toast.makeText(MainActivity.this, "登录失败：" + error.getMessage(), Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    /** 未登录点「开始游戏」时：先拉起 SDK 登录，登录成功才开局；取消/失败留在准备界面。 */
    private void ensureLoginThenStart() {
        if (BaiYouSdk.getInstance().isLoggedIn()) {
            startGameWithRole();
            return;
        }
        BaiYouSdk.getInstance().login(this, new LoginCallback() {
            @Override public void onSuccess(LoginResult result) {
                onLoggedIn();
                Toast.makeText(MainActivity.this, "登录成功，开始游戏", Toast.LENGTH_SHORT).show();
                startGameWithRole();
            }
            @Override public void onCancel() {
                // 拒绝登录不允许进入游戏：提示后直接退出宿主。
                Toast.makeText(MainActivity.this, "未登录无法进入游戏", Toast.LENGTH_SHORT).show();
                finish();
            }
            @Override public void onFailure(SdkError error) {
                // 登录失败同样无法进入游戏：提示后退出宿主。
                Toast.makeText(MainActivity.this, "登录失败：" + error.getMessage(), Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    /** 开局：当前小号必须有角色才放行；隐藏角色卡片，上报当前角色后进入游戏主界面。 */
    private void startGameWithRole() {
        if (roleStore.getActiveRole() == null) {
            Toast.makeText(this, "当前小号还没有角色，请先新建", Toast.LENGTH_SHORT).show();
            showCreateRoleDialog();
            return;
        }
        roleCardPanel.setVisibility(View.GONE);
        reportActiveRole("进入游戏");
        toMainView();
    }

    /**
     * 上报当前角色到小号下（查询 → 不存在则新增 → 归属校验）。
     * 时间字段全部来自真实事件；等级/战力由跨局累计分数与当前局内状态推导。
     */
    private void reportActiveRole(final String reason) {
        if (!BaiYouSdk.getInstance().isLoggedIn()) return;
        final RoleProfile profile = roleStore.getActiveRole();
        if (profile == null) return;
        // 局内实时状态：进入游戏时 mainView 尚未创建，取 0/1 即生命周期值。
        MainView mv = mainView;
        int liveGrade = mv != null ? mv.getGrade() : 1;
        long liveScore = mv != null ? mv.getSumScore() : 0;
        final int level = Math.max(roleStore.levelOf(profile), liveGrade);
        final long power = roleStore.powerOf(profile) + liveScore;
        RoleInfo role = new RoleInfo.Builder()
                .serverId(profile.getServerId()).serverName(profile.getServerName())
                .charId(profile.getCharId()).roleName(profile.getRoleName())
                .level(level).combatPower(power).vipLevel("0")
                .openServerTime(profile.getOpenServerTime())
                .createTime(profile.getCreateTime())
                .loginTime(profile.getLastLoginTime())
                .build();
        Log.i(TAG, reason + " 上报角色：" + profile.getRoleName()
                + " Lv." + level + " 战力 " + power
                + " 建角=" + profile.getCreateTime() + " 开服=" + profile.getOpenServerTime());
        BaiYouSdk.getInstance().reportRole(role, new ResultCallback() {
            @Override public void onSuccess() {
                Log.i(TAG, reason + " 角色上报成功：" + profile.getRoleName() + " Lv." + level + " 战力 " + power);
            }
            @Override public void onFailure(SdkError error) {
                Log.w(TAG, reason + " 角色上报失败：" + error.getMessage());
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "角色上报失败：" + error.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** 准备界面顶部的角色卡片：当前角色信息 + 新建/切换入口。 */
    private void buildRoleCard() {
        roleCardPanel = new LinearLayout(this);
        roleCardPanel.setOrientation(LinearLayout.VERTICAL);
        roleCardPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        roleCardPanel.setBackgroundColor(Color.argb(150, 0, 0, 0));
        roleCardPanel.setPadding(dp(14), dp(8), dp(14), dp(8));

        roleCard = new TextView(this);
        roleCard.setTextColor(Color.WHITE);
        roleCard.setTextSize(14);
        roleCard.setGravity(Gravity.CENTER);
        roleCardPanel.addView(roleCard);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        newRoleButton = makeCardButton("新建角色");
        newRoleButton.setOnClickListener(v -> showCreateRoleDialog());
        switchRoleButton = makeCardButton("切换角色");
        switchRoleButton.setOnClickListener(v -> showSwitchRoleDialog());
        buttons.addView(newRoleButton);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        switchLp.setMargins(dp(10), 0, 0, 0);
        buttons.addView(switchRoleButton, switchLp);
        Button logoutButton = makeCardButton("退出登录");
        logoutButton.setOnClickListener(v -> showLogoutDialog());
        LinearLayout.LayoutParams logoutLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        logoutLp.setMargins(dp(10), 0, 0, 0);
        buttons.addView(logoutButton, logoutLp);
        roleCardPanel.addView(buttons);

        // 渠道信息行：展示 SDK getChannelInfo()（custId / inviteCode），初始化完成后填充。
        channelLine = new TextView(this);
        channelLine.setTextColor(Color.argb(200, 255, 255, 255));
        channelLine.setTextSize(10);
        channelLine.setGravity(Gravity.CENTER);
        channelLine.setPadding(0, dp(6), 0, 0);
        roleCardPanel.addView(channelLine);
        updateChannelLine();

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, dp(40), 0, 0);
        roleCardPanel.setLayoutParams(lp);
        root.addView(roleCardPanel);
        updateRoleCard();
    }

    private Button makeCardButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.argb(200, 30, 90, 180));
        return b;
    }

    /** 刷新角色卡片：当前小号的角色名、区服、等级、战力；未建角色时提示先新建。 */
    private void updateRoleCard() {
        if (roleCard == null || roleStore == null) return;
        final RoleProfile r = roleStore.getActiveRole();
        runOnUiThread(() -> {
            if (r == null) {
                roleCard.setText("未创建角色\n请先「新建角色」");
            } else {
                roleCard.setText(r.getRoleName() + "\n" + r.getServerName()
                        + " · Lv." + roleStore.levelOf(r) + " · 战力 " + roleStore.powerOf(r));
            }
        });
    }

    /** 新建角色：输入角色名 + 选择区服，创建后立即上报（真实建角时间落盘）。 */
    private void showCreateRoleDialog() {
        if (!BaiYouSdk.getInstance().isLoggedIn()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(12), dp(24), 0);
        EditText nameInput = new EditText(this);
        nameInput.setHint("角色名");
        content.addView(nameInput);

        final List<String[]> servers = roleStore.getServers();
        RadioGroup serverGroup = new RadioGroup(this);
        int checkedId = servers.size() > 0 ? 1000 : -1;
        for (int i = 0; i < servers.size(); i++) {
            String[] s = servers.get(i);
            RadioButton rb = new RadioButton(this);
            rb.setText(s[1]);
            rb.setId(1000 + i);
            serverGroup.addView(rb);
            RoleProfile active = roleStore.getActiveRole();
            if (active != null && s[0].equals(active.getServerId())) checkedId = 1000 + i;
        }
        serverGroup.check(checkedId);
        content.addView(serverGroup);

        new AlertDialog.Builder(this)
                .setTitle("新建角色")
                .setView(content)
                .setPositiveButton("创建", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.length() == 0) {
                        Toast.makeText(this, "请输入角色名", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int idx = serverGroup.getCheckedRadioButtonId() - 1000;
                    if (idx < 0 || idx >= servers.size()) idx = 0;
                    String[] s = servers.get(idx);
                    // 新角色绑定当前小号，保证上报归属校验通过。
                    LoginResult result = BaiYouSdk.getInstance().getLoginResult();
                    String subId = result != null ? result.getSubAccountId() : "";
                    String subName = result != null ? result.getSubAccountName() : "";
                    RoleProfile role = roleStore.createRole(name, s[0], s[1], subId, subName);
                    updateRoleCard();
                    reportActiveRole("新建角色");
                    Toast.makeText(this, "已创建角色：" + role.getRoleName(), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 切换角色：列出当前小号已建的角色，选中后切换并重新上报（验证同一小号下多角色归属校验）。 */
    private void showSwitchRoleDialog() {
        if (!BaiYouSdk.getInstance().isLoggedIn()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        // 只切换当前小号自己的角色；其他小号的角色不属于当前身份，不能选中上报。
        LoginResult result = BaiYouSdk.getInstance().getLoginResult();
        String subId = result != null ? result.getSubAccountId() : "";
        final List<RoleProfile> list = roleStore.rolesForSub(subId);
        if (list.isEmpty()) {
            Toast.makeText(this, "当前小号还没有角色，先新建一个", Toast.LENGTH_SHORT).show();
            return;
        }
        if (list.size() < 2) {
            Toast.makeText(this, "当前小号只有一个角色，先新建一个再切换", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            RoleProfile r = list.get(i);
            names[i] = r.getRoleName() + "（" + r.getServerName() + "，Lv." + roleStore.levelOf(r) + "）";
        }
        new AlertDialog.Builder(this)
                .setTitle("切换角色")
                .setItems(names, (d, w) -> {
                    roleStore.switchRole(list.get(w).getCharId());
                    updateRoleCard();
                    reportActiveRole("切换角色");
                    Toast.makeText(this, "已切换到：" + list.get(w).getRoleName(), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 结算页显示「支付复活」按钮：模拟 1 元道具支付，成功后重新开局。 */
    private void showReviveButton() {
        if (reviveButton == null) {
            reviveButton = new Button(this);
            reviveButton.setText("支付复活 ¥1");
            reviveButton.setTextSize(16);
            reviveButton.setTextColor(Color.WHITE);
            reviveButton.setBackgroundColor(Color.argb(180, 0, 120, 220));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.setMargins(0, 0, 0, dp(60));
            reviveButton.setLayoutParams(lp);
            reviveButton.setOnClickListener(v -> startRevivePay());
            root.addView(reviveButton);
        }
        reviveButton.setVisibility(View.VISIBLE);
    }

    /** 发起 1 元游戏充值（带当前角色 ID）；成功回调后重新开局，失败/取消仅提示。 */
    private void startRevivePay() {
        if (!BaiYouSdk.getInstance().isLoggedIn()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        if (paying) return;
        paying = true;
        RoleProfile active = roleStore.getActiveRole();
        PayRequest request = new PayRequest.Builder()
                .productName("复活道具")
                .amountFen(100)
                .charId(active != null ? active.getCharId() : "")
                .build();
        BaiYouSdk.getInstance().pay(this, request, new PayCallback() {
            @Override public void onResult(PayResult result) {
                paying = false;
                if (result.getStatus() == PayResult.Status.SUCCESS) {
                    Toast.makeText(MainActivity.this, "支付成功，继续战斗", Toast.LENGTH_SHORT).show();
                    toMainView();
                } else {
                    Toast.makeText(MainActivity.this, result.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** 购买大招入口：点击游戏内左下角导弹计数旁的"+"按钮，1 元 = 10 颗导弹。
     *  弹确认框防误触；支付成功回调后追加导弹（不受收集上限约束）。 */
    public void startMissilePay() {
        if (paying) return;
        if (!BaiYouSdk.getInstance().isLoggedIn()) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("购买大招")
                .setMessage("花费 1 元购买 10 颗大招，确认支付？")
                .setPositiveButton("确认支付", (d, w) -> {
                    d.dismiss();
                    paying = true;
                    RoleProfile active = roleStore.getActiveRole();
                    PayRequest request = new PayRequest.Builder()
                            .productName("大招补给")
                            .amountFen(100)
                            .charId(active != null ? active.getCharId() : "")
                            .build();
                    BaiYouSdk.getInstance().pay(this, request, new PayCallback() {
                        @Override public void onResult(PayResult result) {
                            paying = false;
                            if (result.getStatus() == PayResult.Status.SUCCESS) {
                                Toast.makeText(MainActivity.this, "购买成功，获得 10 颗大招", Toast.LENGTH_SHORT).show();
                                if (mainView != null) mainView.addMissile(10);
                            } else {
                                Toast.makeText(MainActivity.this, result.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 退出登录：确认后调用 SDK logout，复位角色卡为「未登录」（登出是宿主展示项，SDK 自身 UI 不提供登出按钮）。 */
    private void showLogoutDialog() {
        if (!BaiYouSdk.getInstance().isLoggedIn()) {
            Toast.makeText(this, "当前未登录", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("确认退出当前账号吗？")
                .setPositiveButton("退出", (d, w) -> {
                    BaiYouSdk.getInstance().logout(new ResultCallback() {
                        @Override public void onSuccess() {
                            // 登出回调在主线程：SDK 已清理本地会话并隐藏悬浮球，宿主复位角色卡。
                            roleStore.setActiveForSub("");
                            if (roleCard != null) roleCard.setText("未登录");
                            Toast.makeText(MainActivity.this, "已退出登录", Toast.LENGTH_SHORT).show();
                        }
                        @Override public void onFailure(SdkError error) {
                            Toast.makeText(MainActivity.this,
                                    "退出登录失败：" + error.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 刷新渠道信息行：SDK 初始化后读取 getChannelInfo() 展示 custId / inviteCode。 */
    private void updateChannelLine() {
        if (channelLine == null) return;
        ChannelInfo info = BaiYouSdk.getInstance().getChannelInfo();
        String text = (info == null || info.getCustId().length() == 0)
                ? "未写入渠道数据"
                : "custId: " + info.getCustId() + "  inviteCode: " + info.getInviteCode();
        channelLine.setText(text);
    }

    /**
     * 进入游戏界面
     */
    public void toMainView() {
        if (mainView == null) {
            mainView = new MainView(this, sounds);
        }
        swapGameView(mainView);
        readyView = null;
        endView = null;
        if (reviveButton != null) reviveButton.setVisibility(View.GONE);
        // 进入战斗隐藏悬浮球，避免遮挡游戏画面；回到结算/准备界面再恢复。
        BaiYouSdk.getInstance().hideFloating();
    }

    /**
     * 进入结束分数统计界面：把本局真实得分累计到角色并重新上报（等级/战力成长）。
     *
     * @param score
     */
    public void toEndView(int score) {
        if (endView == null) {
            endView = new EndView(this, sounds);
            endView.setScore(score);
        }
        swapGameView(endView);
        mainView = null;
        // 战斗结束回到结算界面，恢复悬浮球入口（悬浮球随界面状态显示/隐藏）。
        BaiYouSdk.getInstance().showFloating(this);
        // 真实玩法事件：一局结束，累计分数 → 重报角色。
        RoleProfile active = roleStore.getActiveRole();
        if (active != null) {
            roleStore.addScore(active, score);
            updateRoleCard();
            reportActiveRole("结算累计");
        }
        showReviveButton();
    }

    /** 在容器里替换游戏自绘 View：移除旧的游戏视图，新视图插到底层，SDK/角色控件保持在上层。 */
    private void swapGameView(View newView) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View child = root.getChildAt(i);
            if (child != reviveButton && child != roleCardPanel) root.removeView(child);
        }
        root.addView(newView, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /**
     * 结束游戏
     */
    public void endGame() {
        if (readyView != null) {
            readyView.setThreadFlag(false);
        } else if (mainView != null) {
            mainView.setThreadFlag(false);
        } else if (endView != null) {
            endView.setThreadFlag(false);
        }
        this.finish();
    }

    public Handler getHandler() {
        return handler;
    }

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    /** dp 转 px，供叠加控件的边距使用。 */
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 返回键：双击退出游戏。SDK 已移除 exit() 确认框 API，改为宿主侧两击退出。 */
    private long lastBackPressed;
    private static final long BACK_EXIT_INTERVAL_MILLIS = 2000L;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (DebugConstant.DOUBLECLICK_EXIT) {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                long now = System.currentTimeMillis();
                if (now - lastBackPressed <= BACK_EXIT_INTERVAL_MILLIS) {
                    finish();
                } else {
                    lastBackPressed = now;
                    Toast.makeText(this, "再按一次退出游戏", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

}
