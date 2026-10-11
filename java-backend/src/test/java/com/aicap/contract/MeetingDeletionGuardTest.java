package com.aicap.contract;

import com.aicap.common.ApiException;
import com.aicap.service.MeetingDeletionGuard;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MeetingDeletionGuardTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    MeetingDeletionGuard guard;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        guard = new MeetingDeletionGuard(jdbc);
        jdbc.execute("CREATE TABLE meetings(id varchar(36) PRIMARY KEY)");
        jdbc.update("INSERT INTO meetings VALUES ('m1'),('m2')");
        jdbc.execute("CREATE TABLE meeting_transcript_versions(id varchar(36) PRIMARY KEY, meeting_id varchar(36), analysis_meeting_id varchar(36))");
        for (String type : java.util.List.of("status", "planning", "review", "retro", "refinement", "assignment"))
            jdbc.execute("CREATE TABLE meeting_" + type + "_analyses(id varchar(36) PRIMARY KEY, meeting_id varchar(36) REFERENCES meetings(id))");
    }
    @ParameterizedTest @ValueSource(strings={"status","planning","review","retro","refinement","assignment"})
    void eachTypeBlocksBeforeAnyCleanup(String type) {
        jdbc.update("INSERT INTO meeting_" + type + "_analyses VALUES ('a1','m1')");
        var error = assertThrows(ApiException.class, () -> tx.executeWithoutResult(s -> {
            guard.check("m1");
            jdbc.update("DELETE FROM meetings WHERE id='m1'");
        }));
        assertEquals(409,error.getStatus());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM meetings WHERE id='m1'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM meeting_"+type+"_analyses",Integer.class));
        tx.executeWithoutResult(s -> { guard.check("m2"); jdbc.update("DELETE FROM meetings WHERE id='m2'"); });
    }
    @Test void missingMeetingIs404() {
        var error=assertThrows(ApiException.class,()->tx.executeWithoutResult(s->guard.check("missing")));
        assertEquals(404,error.getStatus());
    }
    @Test void sourceAndConfirmedAnalysisMeetingAreRetained() {
        jdbc.update("INSERT INTO meeting_transcript_versions VALUES ('v1','m1','m2')");
        for(var id:java.util.List.of("m1","m2"))
            assertEquals(409,assertThrows(ApiException.class,()->tx.executeWithoutResult(s->guard.check(id))).getStatus());
    }
    @Test void committedSaveWinsAgainstWaitingDeletion() throws Exception {
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var save=pool.submit(()->tx.executeWithoutResult(s->{
                jdbc.queryForList("SELECT id FROM meetings WHERE id='m1' FOR UPDATE");
                locked.countDown();
                try { assertTrue(release.await(3,TimeUnit.SECONDS)); } catch(InterruptedException e){throw new RuntimeException(e);}
                jdbc.update("INSERT INTO meeting_refinement_analyses VALUES ('a1','m1')");
            }));
            assertTrue(locked.await(3,TimeUnit.SECONDS));
            var delete=pool.submit(()->assertThrows(ApiException.class,()->tx.executeWithoutResult(s->guard.check("m1"))).getStatus());
            release.countDown(); save.get(5,TimeUnit.SECONDS);
            assertEquals(409,delete.get(5,TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void deletionWinnerMakesLaterSaveObserveMissingMeeting() throws Exception {
        tx.executeWithoutResult(s->{ guard.check("m1"); jdbc.update("DELETE FROM meetings WHERE id='m1'"); });
        tx.executeWithoutResult(s->assertTrue(jdbc.queryForList("SELECT id FROM meetings WHERE id='m1' FOR UPDATE").isEmpty()));
    }
}
