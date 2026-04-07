package kr.gosky.sso.domain.admin.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DashboardResponse {

    private long totalUsers;
    private long totalClients;
    private long totalLogs;
    private long todayUsers;
}
