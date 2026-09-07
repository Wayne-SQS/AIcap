from pydantic import Field, ValidationError
from .. import models
from .schemas import AgentError, StrictModel


class Search(StrictModel):
    keyword: str = Field(min_length=1, max_length=100)


class Tasks(StrictModel):
    owner_id: int | None = Field(default=None, ge=1)


class Empty(StrictModel):
    pass


TOOL_TYPES = {'search_stories': Search, 'search_pool': Search, 'list_tasks': Tasks,
              'list_members': Empty, 'project_summary': Empty}
DESCRIPTIONS = {
    'search_stories': '按关键词查询真实故事，检查新需求是否已有。最多20条，更多结果明确标记。',
    'search_pool': '按关键词查询真实需求池，避免建议重复需求。最多20条。',
    'list_tasks': '查询已有任务和工时，可按成员ID筛选。当前系统无任务状态或真实容量，不能推断是否过载。',
    'list_members': '查询真实成员ID及显示名，名称有歧义时不能猜测。',
    'project_summary': '查询真实故事统计及当前数据能力限制。',
}
TOOLS = [{'type': 'function', 'function': {'name': name, 'description': DESCRIPTIONS[name],
          'parameters': schema.model_json_schema()}} for name, schema in TOOL_TYPES.items()]


def execute_tool(session_factory, user_id, name, arguments):
    if name not in TOOL_TYPES:
        raise AgentError('tool_not_allowed', '模型请求了未授权工具，分析已停止')
    try:
        values = TOOL_TYPES[name].model_validate(arguments)
    except ValidationError:
        raise AgentError('invalid_tool_arguments', '模型工具参数无效')
    with session_factory() as db:
        user = db.get(models.User, user_id)
        if user is None or user.role not in ('admin', 'owner', 'member'):
            raise AgentError('permission_changed', '发起人的权限已变化，分析已停止')
        if name == 'project_summary':
            stories = db.query(models.Story.status).all()
            return {'stories': len(stories), 'done': sum(s == 2 for (s,) in stories),
                    'current_sprint': None, 'member_capacity': None,
                    'limitations': ['未配置当前Sprint日期', '未持久化成员容量', '任务无状态字段',
                                    '本轮只能提出新增需求池建议，其他变更请人工处理']}
        if name == 'list_members':
            rows = db.query(models.User).order_by(models.User.id).limit(101).all()
            return {'items': [{'id': u.id, 'name': u.display_name} for u in rows[:100]], 'truncated': len(rows) > 100}
        if name == 'list_tasks':
            query = db.query(models.Task)
            if values.owner_id:
                query = query.filter(models.Task.owner_id == values.owner_id)
            rows = query.order_by(models.Task.id).limit(21).all()
            return {'items': [{'id': t.id, 'name': t.name, 'owner_id': t.owner_id, 'hours': t.hours,
                               'story_ref': t.story_ref, 'week_start': t.week_start, 'week_end': t.week_end}
                              for t in rows[:20]], 'truncated': len(rows) > 20,
                    'limitations': '工时是计划值，缺少任务状态和真实容量，不能计算完成度或过载'}
        cls = models.Story if name == 'search_stories' else models.PoolItem
        rows = db.query(cls).filter(cls.title.contains(values.keyword, autoescape=True)).order_by(cls.id).limit(21).all()
        items = [{'id': r.id, 'title': r.title, 'description': r.description[:1000], 'priority': r.priority} for r in rows[:20]]
        if name == 'search_stories':
            for item, row in zip(items, rows):
                item.update(status=row.status, owner_id=row.owner_id, sprint=row.sprint)
        return {'items': items, 'truncated': len(rows) > 20}
