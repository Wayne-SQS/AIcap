package com.aicap.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EfficiencyRulesTest {
    private static final LocalDate TODAY=LocalDate.of(2026,10,23);
    private EfficiencyRules.TaskFact task(String code,int status,int progress,int owner,List<String> core,String due,String since,int regressions,String title) {
        return new EfficiencyRules.TaskFact(code,title,"US01",owner,status,progress,10,false,core,
            due==null?null:LocalDate.parse(due),since==null?null:LocalDate.parse(since),regressions);
    }
    private List<Map<String,Object>> run(List<EfficiencyRules.TaskFact> tasks,List<EfficiencyRules.MemberFact> members,
                                         List<EfficiencyRules.ActivityFact> acts) {
        return EfficiencyRules.analyze(tasks,members,acts,TODAY);
    }
    @Test void overdueCorePredecessorIdentifiesAffectedSuccessor() {
        var out=run(List.of(task("T01",1,40,1,List.of(),"2026-10-01",null,0,"接口实现"),
            task("T02",0,0,2,List.of("T01"),null,null,0,"页面集成")),List.of(),List.of());
        var issue=out.stream().filter(x->x.get("type").equals("TASK_BLOCKING")).findFirst().orElseThrow();
        assertEquals(List.of("T01","T02"),issue.get("affectedTaskCodes"));
    }
    @Test void explicitBlockedFlagIdentifiesIssueWithoutInventingSuccessors() {
        var out=run(List.of(new EfficiencyRules.TaskFact("T01","接口","US01",1,0,0,10,true,List.of(),null,null,0)),
            List.of(),List.of());
        assertEquals("TASK_BLOCKING",out.getFirst().get("type"));
        assertEquals(List.of("T01"),out.getFirst().get("affectedTaskCodes"));
    }
    @Test void weakEdgesAreNotInputsToHardBlockingRule() {
        assertTrue(run(List.of(task("T01",1,10,1,List.of(),"2026-10-01",null,0,"做接口"),
            task("T02",0,0,2,List.of(),"2026-10-01",null,0,"普通任务")),List.of(),List.of()).isEmpty());
    }
    @Test void overloadOnlySuggestsCandidateWithSkillAndCapacityEvidence() {
        var tasks=List.of(task("T01",0,0,1,List.of(),null,null,0,"Java 报表接口"));
        var members=List.of(new EfficiencyRules.MemberFact(1,"A",100,120,List.of(),List.of()),
            new EfficiencyRules.MemberFact(2,"B",100,20,List.of("Java"),List.of()));
        var issue=run(tasks,members,List.of()).getFirst();
        assertEquals("WORKLOAD_IMBALANCE",issue.get("type"));
        assertEquals(1,((List<?>)issue.get("candidate_assignments")).size());
        var noMatch=run(tasks,List.of(members.getFirst(),new EfficiencyRules.MemberFact(3,"C",100,20,List.of("UX"),List.of())),List.of()).getFirst();
        assertTrue(((List<?>)noMatch.get("candidate_assignments")).isEmpty());
        assertFalse(noMatch.get("recommendations").toString().contains("候选人的技能"));
    }
    @Test void commitVolumeAloneIsOnlyInsufficientReworkSignal() {
        var acts=List.of(new EfficiencyRules.ActivityFact("1","T01","auth","commit","github","a"),
            new EfficiencyRules.ActivityFact("2","T01","auth","commit","github","b"),
            new EfficiencyRules.ActivityFact("3","T01","auth","commit","github","c"));
        var issue=run(List.of(task("T01",1,30,1,List.of(),null,null,0,"认证")),List.of(),acts).getFirst();
        assertEquals("REWORK_SIGNAL",issue.get("type"));
        assertEquals(false,issue.get("evidence_sufficient"));
        assertEquals("LOW",issue.get("severity"));
    }
    @Test void repeatedBugfixOrStatusRegressionIsReworkEvidence() {
        var acts=List.of(new EfficiencyRules.ActivityFact("1","T01","auth","bugfix","github","修复"),
            new EfficiencyRules.ActivityFact("2","T01","auth","bugfix","github","再次修复"),
            new EfficiencyRules.ActivityFact("3","T01","auth","bugfix","github","修复回归"));
        var issue=run(List.of(task("T01",1,30,1,List.of(),null,null,0,"认证")),List.of(),acts).getFirst();
        assertEquals(true,issue.get("evidence_sufficient"));
    }
    @Test void longTestingWaitProducesProcessDelayWithCaveat() {
        var issue=run(List.of(task("T01",1,40,1,List.of(),null,"2026-10-01",0,"集成测试验收")),List.of(),List.of()).getFirst();
        assertEquals("PROCESS_DELAY",issue.get("type"));
        assertTrue(issue.get("possibleCauses").toString().contains("不能证明"));
    }
    @Test void missingActualHoursNeverInventsVariance() {
        assertEquals(false, EfficiencyRules.actualVariance(10, null).get("available"));
    }
    @Test void actualHoursVarianceUsesRealValues() {
        var result = EfficiencyRules.actualVariance(40, 48);
        assertEquals(true, result.get("available"));
        assertEquals(8, result.get("varianceHours"));
        assertEquals(.2, (Double) result.get("varianceRate"), .0001);
    }
    @Test void blockedTaskQuantifiesEntireOpenDependencyChain() {
        var out=run(List.of(
            new EfficiencyRules.TaskFact("T01","API","US01",1,1,40,8,true,List.of(),null,null,0),
            new EfficiencyRules.TaskFact("T02","UI","US01",2,0,0,5,false,List.of("T01"),null,null,0),
            new EfficiencyRules.TaskFact("T03","Test","US01",2,0,0,3,false,List.of("T02"),null,null,0)),List.of(),List.of());
        var issue=out.stream().filter(x -> x.get("type").equals("TASK_BLOCKING")).findFirst().orElseThrow();
        assertEquals(2, issue.get("impactChainLength"));
        assertEquals(List.of("T01","T02","T03"), issue.get("affectedTaskCodes"));
        assertTrue(issue.containsKey("reasoning"));
        assertEquals(true, issue.get("requiresHumanConfirmation"));
    }
    @Test void reviewWaitUsesRecordedCreationAndReviewTimes() {
        var process=List.of(new EfficiencyRules.ProcessFact("P1","efficiency_proposal","approved",
            java.time.LocalDateTime.parse("2026-10-01T10:00:00"),java.time.LocalDateTime.parse("2026-10-03T22:00:00")));
        var issue=EfficiencyRules.analyze(List.of(),List.of(),List.of(),process,TODAY).getFirst();
        assertEquals("PROCESS_REVIEW_CYCLE",issue.get("type"));
        var impact=(Map<?,?>)issue.get("quantifiedImpact");
        assertEquals(2.5,(Double)impact.get("averageWaitDays"),.0001);
    }
    @Test void allInsightsExposeAcceptanceContractFields() {
        var issue=run(List.of(new EfficiencyRules.TaskFact("T01","blocked","US01",1,0,0,8,true,List.of(),null,null,0)),List.of(),List.of()).getFirst();
        for(String key:List.of("type","severity","title","summary","evidence","reasoning","recommendations","confidence","quantifiedImpact","requiresHumanConfirmation"))
            assertTrue(issue.containsKey(key), "missing field "+key);
        assertTrue(((Map<?,?>)((List<?>)issue.get("recommendations")).getFirst()).containsKey("recheckMetric"));
    }
    @Test void growingHistoricalBacklogProducesQuantifiedInsightAndRecheckTarget() {
        var issue=EfficiencyRules.backlogGrowth(4,9,LocalDate.of(2026,9,25),LocalDate.of(2026,10,23),.9,List.of("T09"));
        assertEquals("TASK_BACKLOG_GROWTH",issue.get("type"));
        assertEquals(5,((Map<?,?>)issue.get("quantifiedImpact")).get("backlogIncrease"));
        assertEquals(0,((Map<?,?>)issue.get("recheckMetric")).get("target"));
        assertEquals(List.of("T09"),issue.get("affectedTaskCodes"));
    }
}
