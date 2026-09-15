package com.aicap.profile;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GitHub 同步配置(US34:GitHub 仓库与成员映射)。
 *
 * 环境变量(前缀 GITHUB_*):
 *   GITHUB_ENABLED       默认 false;false 时同步接口返回"未配置"状态,不发起任何外部请求
 *   GITHUB_TOKEN         GitHub Personal Access Token(只需 repo 只读权限,不要公开)
 *   GITHUB_REPO          仓库 owner/repo,如 neusoft/cloud-brain
 *   GITHUB_API_BASE      默认 https://api.github.com
 *   GITHUB_USER_MAPPING  JSON 字符串:GitHub 登录名 → 系统 username,如 {"li-ming":"李锐铭"}
 *   GITHUB_AUTO_SYNC_HOURS 定时自动同步回溯窗口(小时),0=不自动
 *
 * 成员映射优先级:配置 user-mapping-json 的 GitHub 登录名 → commit 作者邮箱本地部分 → 提交者名。
 * 未匹配到系统成员的活动不写库,记入 warnings(不造假:无法归属的活动不进入画像)。
 */
@Data
@Component
@ConfigurationProperties(prefix = "github")
public class GitHubProperties {
    /** 总开关;false 时同步接口直接返回未配置状态 */
    private boolean enabled = false;
    /** GitHub PAT(repo 只读权限即可) */
    private String token = "";
    /** 仓库 owner/repo */
    private String repo = "";
    private String apiBase = "https://api.github.com";
    /** GitHub 登录名 → 系统 username 的映射(JSON 字符串,由同步服务解析) */
    private String userMappingJson = "";
    /** 定时自动同步回溯窗口(小时);0=不自动 */
    private int autoSyncHours = 0;
    /** 单次同步最大拉取页数(每类最多 maxPages×100 条;活跃仓库超出会截断,可在同步结果看到 pulled 数量核对) */
    private int maxPages = 5;
    /** 单次同步拉取 PR 的 Reviews 上限(每个 PR 1 个请求,默认 30 个 PR;超出范围的 review 留待下轮) */
    private int reviewPrLimit = 30;
}
