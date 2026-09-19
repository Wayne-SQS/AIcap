package com.aicap.rag;

import com.aicap.entity.Meeting;
import com.aicap.entity.MemberProfile;
import com.aicap.entity.PoolItem;
import com.aicap.entity.Story;
import com.aicap.entity.Task;
import com.aicap.entity.User;
import com.aicap.mapper.MeetingMapper;
import com.aicap.mapper.MemberProfileMapper;
import com.aicap.mapper.PoolItemMapper;
import com.aicap.mapper.StoryMapper;
import com.aicap.mapper.TaskMapper;
import com.aicap.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 扫描可检索的数据源,交给 {@link Chunker} 切成知识块。
 *
 * <p>这一层只负责「取数据 + 交给切分器」,不做任何切分判断 ——
 * 切分策略集中在 {@link Chunker},避免同一件事有两个地方各自为政。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeSourceScanner {

    /** 单个文档文件大小上限:超过的多半是导出物而非文档,索引它没有价值 */
    private static final long MAX_DOC_BYTES = 512 * 1024;

    private final StoryMapper storyMapper;
    private final PoolItemMapper poolItemMapper;
    private final TaskMapper taskMapper;
    private final MeetingMapper meetingMapper;
    private final MemberProfileMapper memberProfileMapper;
    private final UserMapper userMapper;
    private final Chunker chunker;
    private final RagProperties props;

    /** 全量扫描:所有源类型。顺序固定(按 id 升序),保证 chunk 序号可复现。 */
    public List<Chunk> scanAll() {
        List<Chunk> all = new ArrayList<>();
        all.addAll(scanStories());
        all.addAll(scanPoolItems());
        all.addAll(scanTasks());
        all.addAll(scanMeetings());
        all.addAll(scanProfiles());
        all.addAll(scanDocs());
        return all;
    }

    /** 单源扫描:源记录写操作后重建该条(供 S3 增量接入调用)。源已不存在时返回空列表。 */
    public List<Chunk> scanOne(String sourceType, String sourceId) {
        return switch (sourceType) {
            case Chunk.SRC_STORY -> {
                Story s = storyMapper.selectById(sourceId);
                yield s == null ? List.of() : chunker.chunkStory(s);
            }
            case Chunk.SRC_POOL_ITEM -> {
                PoolItem p = poolItemMapper.selectById(sourceId);
                yield p == null ? List.of() : chunker.chunkPoolItem(p);
            }
            case Chunk.SRC_TASK -> {
                Task t = taskMapper.selectById(sourceId);
                yield t == null ? List.of() : chunker.chunkTask(t, storyTitles());
            }
            case Chunk.SRC_MEETING -> {
                Meeting m = meetingMapper.selectById(sourceId);
                yield m == null ? List.of() : chunker.chunkMeeting(m);
            }
            case Chunk.SRC_PROFILE -> {
                int userId = Integer.parseInt(sourceId);
                MemberProfile p = memberProfileMapper.selectOne(
                        new QueryWrapper<MemberProfile>().eq("user_id", userId));
                yield p == null ? List.of() : chunker.chunkProfile(p, userMapper.selectById(userId));
            }
            case Chunk.SRC_DOC -> {
                Path file = docsRoot() == null ? null : docsRoot().resolve(sourceId);
                if (file == null || !Files.isRegularFile(file)) {
                    yield List.of();
                }
                yield chunker.chunkDoc(sourceId, readDoc(file, sourceId));
            }
            default -> List.of();
        };
    }

    // ------------------------------------------------------------------

    private List<Chunk> scanStories() {
        List<Chunk> out = new ArrayList<>();
        for (Story s : storyMapper.selectList(new QueryWrapper<Story>().orderByAsc("id"))) {
            out.addAll(chunker.chunkStory(s));
        }
        return out;
    }

    private List<Chunk> scanPoolItems() {
        List<Chunk> out = new ArrayList<>();
        for (PoolItem p : poolItemMapper.selectList(new QueryWrapper<PoolItem>().orderByAsc("id"))) {
            out.addAll(chunker.chunkPoolItem(p));
        }
        return out;
    }

    private List<Chunk> scanTasks() {
        Map<String, String> titles = storyTitles();
        List<Chunk> out = new ArrayList<>();
        for (Task t : taskMapper.selectList(new QueryWrapper<Task>().orderByAsc("id"))) {
            out.addAll(chunker.chunkTask(t, titles));
        }
        return out;
    }

    private List<Chunk> scanMeetings() {
        List<Chunk> out = new ArrayList<>();
        for (Meeting m : meetingMapper.selectList(new QueryWrapper<Meeting>().orderByAsc("id"))) {
            out.addAll(chunker.chunkMeeting(m));
        }
        return out;
    }

    private List<Chunk> scanProfiles() {
        Map<Integer, User> users = new HashMap<>();
        for (User u : userMapper.selectList(null)) {
            users.put(u.getId(), u);
        }
        List<Chunk> out = new ArrayList<>();
        for (MemberProfile p : memberProfileMapper.selectList(
                new QueryWrapper<MemberProfile>().orderByAsc("user_id"))) {
            out.addAll(chunker.chunkProfile(p, users.get(p.getUserId())));
        }
        return out;
    }

    /** 卡 id → 故事标题(任务块里把 kanban_card_id 展开成人看得懂的名字) */
    private Map<String, String> storyTitles() {
        Map<String, String> titles = new LinkedHashMap<>();
        for (Story s : storyMapper.selectList(null)) {
            titles.put(s.getId(), s.getTitle());
        }
        return titles;
    }

    // ------------------------------------------------------------------
    // 项目文档
    // ------------------------------------------------------------------

    private List<Chunk> scanDocs() {
        Path root = docsRoot();
        if (root == null) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    // 跳过隐藏目录(如 .git)与 node_modules
                    .filter(p -> {
                        for (Path seg : root.relativize(p)) {
                            String name = seg.toString();
                            if (name.startsWith(".") || "node_modules".equals(name)) return false;
                        }
                        return true;
                    })
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("扫描文档目录失败,本次跳过 doc 源: {}", e.getMessage());
            return List.of();
        }
        // 排序保证多次索引得到相同的 chunk_index
        files.sort(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')));

        List<Chunk> out = new ArrayList<>();
        for (Path file : files) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String text = readDoc(file, rel);
            if (text != null) {
                out.addAll(chunker.chunkDoc(rel, text));
            }
        }
        return out;
    }

    /**
     * 目录不可解析时返回 null 而不是抛异常:文档源是「锦上添花」,
     * 不该因为部署时少了 docs/ 就让整个索引任务失败。
     */
    private Path docsRoot() {
        String dir = props.getDocsDir();
        if (dir == null || dir.isBlank()) {
            return null;
        }
        Path p = Paths.get(dir).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            log.warn("文档目录不存在,本次跳过 doc 源: {}", p);
            return null;
        }
        return p;
    }

    private String readDoc(Path file, String rel) {
        try {
            if (Files.size(file) > MAX_DOC_BYTES) {
                log.debug("文档超过 {} 字节,跳过: {}", MAX_DOC_BYTES, rel);
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取文档失败,跳过 {}: {}", rel, e.getMessage());
            return null;
        }
    }
}
