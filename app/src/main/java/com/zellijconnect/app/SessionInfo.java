package com.zellijconnect.app;

import org.json.JSONObject;

/**
 * Data class representing a Zellij session with Claude status and git info.
 */
public class SessionInfo {
    public final String name;
    public final String workingDirectory;

    // Claude status
    public final String claudeStatus; // "working", "idle", "waiting", "unknown"
    public final String claudeActivity;
    public final String claudeDescription;

    // Git status
    public final String gitBranch;
    public final boolean mergedToDev;
    public final boolean remoteBranchExists;
    public final boolean hasUncommittedChanges;
    public final int unpushedCommitCount;

    public SessionInfo(String name, String workingDirectory,
                      String claudeStatus, String claudeActivity, String claudeDescription,
                      String gitBranch, boolean mergedToDev, boolean remoteBranchExists,
                      boolean hasUncommittedChanges, int unpushedCommitCount) {
        this.name = name;
        this.workingDirectory = workingDirectory;
        this.claudeStatus = claudeStatus;
        this.claudeActivity = claudeActivity;
        this.claudeDescription = claudeDescription;
        this.gitBranch = gitBranch;
        this.mergedToDev = mergedToDev;
        this.remoteBranchExists = remoteBranchExists;
        this.hasUncommittedChanges = hasUncommittedChanges;
        this.unpushedCommitCount = unpushedCommitCount;
    }

    public static SessionInfo fromJson(JSONObject json) {
        try {
            String name = json.optString("name", "unknown");
            String workingDirectory = json.optString("workingDirectory", null);

            // Parse claude status
            JSONObject claude = json.optJSONObject("claude");
            String claudeStatus = "unknown";
            String claudeActivity = null;
            String claudeDescription = null;
            if (claude != null) {
                claudeStatus = claude.optString("status", "unknown");
                claudeActivity = claude.optString("activity", null);
                String desc = claude.optString("description", null);
                if (desc != null && !desc.equals("null") && !desc.isEmpty()) {
                    claudeDescription = desc;
                }
            }

            // Parse git status
            JSONObject git = json.optJSONObject("git");
            String gitBranch = null;
            boolean mergedToDev = false;
            boolean remoteBranchExists = true;
            boolean hasUncommittedChanges = false;
            int unpushedCommitCount = 0;
            if (git != null) {
                gitBranch = git.optString("branch", null);
                mergedToDev = git.optBoolean("mergedToDev", false);
                remoteBranchExists = git.optBoolean("remoteBranchExists", true);
                hasUncommittedChanges = git.optBoolean("hasUncommittedChanges", false);
                unpushedCommitCount = git.optInt("unpushedCommitCount", 0);
            }

            return new SessionInfo(name, workingDirectory,
                                  claudeStatus, claudeActivity, claudeDescription,
                                  gitBranch, mergedToDev, remoteBranchExists,
                                  hasUncommittedChanges, unpushedCommitCount);
        } catch (Exception e) {
            return new SessionInfo("error", null, "unknown", null, null, null, false, false, false, 0);
        }
    }
}
