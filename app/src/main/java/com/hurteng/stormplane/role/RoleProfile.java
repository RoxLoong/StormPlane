package com.hurteng.stormplane.role;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 游戏侧持久化的角色档案。
 *
 * <p>每个角色携带真实的建角/开服/登录时间（由游戏事件落盘，SDK 上报时后台会校验，
 * 缺失即拒绝）：建角时间 = 玩家真正创建该角色的时刻；开服时间 = 该区服第一个角色
 * 创建的时刻（单机游戏没有真实服务器，以「区服首个玩家入驻」作为开服事件）；
 * 登录时间 = 每次进入游戏刷新。跨局累计分数（totalScore）按游戏自身的
 * 「每 {@code LEVELUP_SCORE} 分升一级、上限 {@code MAXGRADE}」规则推导等级与战力。
 *
 * <p>{@code roleId} 是后台小号下角色的 {@code charId}，上报时用于归属校验。
 * 角色绑定到所属小号（{@code subAccountId}），切换小号后必须改用新小号的角色，
 * 不能沿用上一个小号的角色，否则归属校验会失败。</p>
 */
public class RoleProfile {
    private String roleId;          // 对应后台 charId
    private String roleName;
    private String serverId;
    private String serverName;
    private String subAccountId;    // 所属小号 ID（平台账号身份，对应后台 smallId）
    private String subAccountName;  // 所属小号名称（展示用）
    private String createTime;      // 建角时间（真实事件）
    private String openServerTime;  // 开服时间（真实事件：该区首个角色创建时）
    private String lastLoginTime;   // 最近登录时间（真实事件：每次进入游戏）
    private long totalScore;        // 跨局累计分数（真实玩法数据）

    public String getRoleId() { return roleId; }
    public void setRoleId(String roleId) { this.roleId = roleId; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public String getServerId() { return serverId; }
    public void setServerId(String serverId) { this.serverId = serverId; }
    public String getServerName() { return serverName; }
    public void setServerName(String serverName) { this.serverName = serverName; }
    public String getSubAccountId() { return subAccountId; }
    public void setSubAccountId(String subAccountId) { this.subAccountId = subAccountId; }
    public String getSubAccountName() { return subAccountName; }
    public void setSubAccountName(String subAccountName) { this.subAccountName = subAccountName; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public String getOpenServerTime() { return openServerTime; }
    public void setOpenServerTime(String openServerTime) { this.openServerTime = openServerTime; }
    public String getLastLoginTime() { return lastLoginTime; }
    public void setLastLoginTime(String lastLoginTime) { this.lastLoginTime = lastLoginTime; }
    public long getTotalScore() { return totalScore; }
    public void setTotalScore(long totalScore) { this.totalScore = totalScore; }

    /** 序列化为 JSON 持久化。 */
    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("roleId", roleId)
                .put("roleName", roleName)
                .put("serverId", serverId)
                .put("serverName", serverName)
                .put("subAccountId", subAccountId)
                .put("subAccountName", subAccountName)
                .put("createTime", createTime)
                .put("openServerTime", openServerTime)
                .put("lastLoginTime", lastLoginTime)
                .put("totalScore", totalScore);
    }

    /** 从持久化的 JSON 恢复。 */
    public static RoleProfile fromJson(JSONObject o) {
        RoleProfile r = new RoleProfile();
        r.roleId = o.optString("roleId");
        r.roleName = o.optString("roleName");
        r.serverId = o.optString("serverId");
        r.serverName = o.optString("serverName");
        r.subAccountId = o.optString("subAccountId");
        r.subAccountName = o.optString("subAccountName");
        r.createTime = o.optString("createTime");
        r.openServerTime = o.optString("openServerTime");
        r.lastLoginTime = o.optString("lastLoginTime");
        r.totalScore = o.optLong("totalScore", 0);
        return r;
    }
}
