package com.aicap.generation;

import com.aicap.common.ApiException;
import com.aicap.entity.User;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectGenerationImportServiceTest {
    @Mock private UserMapper users;

    private ProjectGenerationImportService importer;
    private User gaosi;
    private User sun;

    @BeforeEach
    void setUp() {
        gaosi = member(2, "高思晗");
        sun = member(3, "孙秋实");
        lenient().when(users.selectList(any(Wrapper.class))).thenReturn(List.of(gaosi));
        lenient().when(users.selectById(2)).thenReturn(gaosi);
        lenient().when(users.selectById(3)).thenReturn(sun);
        importer = new ProjectGenerationImportService(new ObjectMapper(), users);
    }

    @Test
    void ownerNameMapsToRealMemberId() {
        var draft = importer.parse(csv("title,hours,owner_name\n注册后端,12, 高思晗 \n"));
        assertEquals(2, draft.path("tasks").get(0).path("owner_id").asInt());
    }

    @Test
    void ownerIdRemainsSupportedAndHasPriorityWhenConsistent() {
        var draft = importer.parse(csv("title,hours,owner_id,owner_name\n注册后端,12,2,高思晗\n"));
        assertEquals(2, draft.path("tasks").get(0).path("owner_id").asInt());
    }

    @Test
    void unknownOwnerNameIsExplicit() {
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of());
        ApiException error = assertThrows(ApiException.class,
                () -> importer.parse(csv("title,hours,owner_name\n注册后端,12,不存在的人\n")));
        assertEquals("负责人不存在：不存在的人", error.getMessage());
    }

    @Test
    void unknownOwnerIdIsExplicit() {
        ApiException error = assertThrows(ApiException.class,
                () -> importer.parse(csv("title,hours,owner_id\n注册后端,12,0\n")));
        assertEquals("负责人不存在：0", error.getMessage());
    }

    @Test
    void emptyOwnerIsLeftForExistingDraftDefaultingRule() {
        var draft = importer.parse(csv("title,hours\n注册后端,12\n"));
        if (draft.path("tasks").get(0).has("owner_id")) {
            throw new AssertionError("empty owner should not be converted to a fake member id during import");
        }
    }

    @Test
    void ConflictingOwnerIdAndNameIsRejected() {
        when(users.selectList(any(Wrapper.class))).thenReturn(List.of(sun));
        ApiException error = assertThrows(ApiException.class,
                () -> importer.parse(csv("title,hours,owner_id,owner_name\n注册后端,12,2,孙秋实\n")));
        assertEquals("负责人编号与姓名不一致：2 / 孙秋实", error.getMessage());
    }

    private User member(int id, String name) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(name);
        return user;
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "import.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
