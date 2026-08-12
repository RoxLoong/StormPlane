package com.hurteng.stormplane.role;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import com.hurteng.stormplane.constant.GameConstant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 角色档案本地存储（SharedPreferences）。
 *
 * <p>角色与区服数据全部来自游戏真实事件，SDK 上报时后台会校验时间字段：
 * <ul>
 *   <li>建角时间：玩家真正创建该角色的时刻（每次 {@link #createRole} 记录）。</li>
 *   <li>开服时间：该区服首个角色创建的时刻（单机游戏没有真实服务器，
 *       以「区服首个玩家入驻」作为开服事件，按区服持久化）。</li>
 *   <li>登录时间：每次进入游戏（{@link #touchLogin}）刷新。</li>
 *   <li>跨局累计分数：每局结束（{@link #addScore}）累加真实得分，
 *       按游戏自身 6 级规则推导等级与战力。</li>
 * </ul>
 */
public class RoleProfileStore {
    private static final String TAG = "StormPlane.RoleStore";
    private static final String PREF = "stormplane_role_profile";
    private static final String KEY_ROLES = "roles";
    private static final String KEY_ACTIVE = "active_role_id";
    private static final String KEY_SERVER_OPEN = "server_open_times"; // JSONObject: serverId -> 开服时间

    private final SharedPreferences prefs;
    private final List<RoleProfile> roles = new ArrayList<>();
    private final List<String[]> servers; // {serverId, serverName}
    private String activeCharId;

    public RoleProfileStore(Context context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        servers = defaultServers();
        load();
    }

    /** 本游戏可选的区服。 */
    private List<String[]> defaultServers() {
        List<String[]> list = new ArrayList<>();
        list.add(new String[]{"storm-1", "风暴一区"});
        list.add(new String[]{"storm-2", "风暴二区"});
        return list;
    }

    public List<String[]> getServers() { return servers; }

    public List<RoleProfile> getRoles() { return roles; }

    public boolean hasRoles() { return !roles.isEmpty(); }

    /** 当前选中的角色；未设置当前角色或当前角色已不存在时返回 null。 */
    public RoleProfile getActiveRole() {
        if (activeCharId == null) return null;
        for (RoleProfile r : roles) {
            if (r.getCharId().equals(activeCharId)) return r;
        }
        return null;
    }

    /** 指定小号下已创建的角色，无则返回 null。 */
    public RoleProfile findRoleForSub(String subAccountId) {
        if (subAccountId == null) return null;
        for (RoleProfile r : roles) {
            if (subAccountId.equals(r.getSubAccountId())) return r;
        }
        return null;
    }

    /** 指定小号下的全部角色。 */
    public List<RoleProfile> rolesForSub(String subAccountId) {
        List<RoleProfile> list = new ArrayList<>();
        if (subAccountId == null) return list;
        for (RoleProfile r : roles) {
            if (subAccountId.equals(r.getSubAccountId())) list.add(r);
        }
        return list;
    }

    /**
     * 把当前角色设为指定小号已有的角色；该小号还没建过角色时清空当前角色（返回 null）。
     * 切换小号后调用，绝不沿用上一个小号的角色；角色必须由玩家显式创建。
     */
    public RoleProfile setActiveForSub(String subAccountId) {
        RoleProfile r = findRoleForSub(subAccountId);
        activeCharId = r != null ? r.getCharId() : null;
        save();
        return r;
    }

    /**
     * 创建角色（绑定当前小号）：记录真实建角时间；该区首个角色创建时记录开服时间；
     * 新角色自动设为当前角色并落盘。
     */
    public RoleProfile createRole(String roleName, String serverId, String serverName,
                                  String subAccountId, String subAccountName) {
        RoleProfile role = new RoleProfile();
        role.setCharId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        role.setRoleName(roleName);
        role.setServerId(serverId);
        role.setServerName(serverName);
        role.setSubAccountId(subAccountId);
        role.setSubAccountName(subAccountName);
        role.setTotalScore(0);
        String now = now();
        role.setCreateTime(now);
        role.setLastLoginTime(now);
        roles.add(role);
        activeCharId = role.getCharId();
        // 该区首次建角即「开服」：开服时间来自真实事件，按区服持久化。
        JSONObject opens = serverOpens();
        if (!opens.has(serverId)) {
            try { opens.put(serverId, now); } catch (Exception ignored) { }
        }
        role.setOpenServerTime(openServerTimeOf(serverId));
        save();
        return role;
    }

    /** 切换当前角色。 */
    public void switchRole(String charId) {
        for (RoleProfile r : roles) {
            if (r.getCharId().equals(charId)) {
                activeCharId = charId;
                save();
                return;
            }
        }
    }

    /** 进入游戏时刷新当前角色的最近登录时间（真实事件）。 */
    public void touchLogin() {
        RoleProfile role = getActiveRole();
        if (role == null) return;
        role.setLastLoginTime(now());
        save();
    }

    /** 一局结束后把本局真实得分累计到角色，等级/战力随之成长。 */
    public void addScore(RoleProfile role, long score) {
        if (role == null) return;
        role.setTotalScore(role.getTotalScore() + Math.max(0, score));
        save();
    }

    /** 角色等级：按游戏自身的「每 LEVELUP_SCORE 分升一级、上限 MAXGRADE」规则由累计分数推导。 */
    public int levelOf(RoleProfile role) {
        if (role == null) return 1;
        long lv = 1 + role.getTotalScore() / GameConstant.LEVELUP_SCORE;
        return (int) Math.min(lv, GameConstant.MAXGRADE);
    }

    /** 角色战力：由累计分数与等级推导，反映真实成长。 */
    public long powerOf(RoleProfile role) {
        if (role == null) return 0;
        return role.getTotalScore() + (levelOf(role) - 1) * 1000L;
    }

    /** 某区服的开服时间（首次建角时记录），缺失返回空串（正常不会发生）。 */
    public String openServerTimeOf(String serverId) {
        JSONObject opens = serverOpens();
        String t = opens.optString(serverId, "");
        if (TextUtils.isEmpty(t)) {
            t = now();
            try { opens.put(serverId, t); saveServerOpens(opens); } catch (Exception ignored) { }
        }
        return t;
    }

    private void load() {
        roles.clear();
        String json = prefs.getString(KEY_ROLES, "");
        if (!TextUtils.isEmpty(json)) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    roles.add(RoleProfile.fromJson(arr.getJSONObject(i)));
                }
            } catch (Exception e) {
                Log.w(TAG, "角色档案解析失败，重置", e);
                roles.clear();
            }
        }
        activeCharId = prefs.getString(KEY_ACTIVE, null);
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (RoleProfile r : roles) arr.put(r.toJson());
            prefs.edit().putString(KEY_ROLES, arr.toString())
                    .putString(KEY_ACTIVE, activeCharId)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "角色档案保存失败", e);
        }
    }

    private JSONObject serverOpens() {
        String json = prefs.getString(KEY_SERVER_OPEN, "");
        if (!TextUtils.isEmpty(json)) {
            try { return new JSONObject(json); } catch (Exception ignored) { }
        }
        return new JSONObject();
    }

    private void saveServerOpens(JSONObject opens) {
        prefs.edit().putString(KEY_SERVER_OPEN, opens.toString()).apply();
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
