-- 관계 기록: 👀 먼저 한 행동 (2026-11-18 룡)
-- 일이 생겼을 때 그 사람이 시키지 않아도 먼저 한 행동을 사람별로 쌓는다(은사 찾기).
-- 2026-11-18 슈파베이스 「룡룡룡」에 적용함.
-- 꼴 = 날짜 · 상황(비워도 됨) · 먼저 한 행동. 해석 없이 본 그대로. 나만 본다(created_by = 본인).
-- 꼴은 기존 rel_ 함수와 같다: rel_member(p_token)로 본인 확인 · security definer · 결과는 json, 틀리면 {error}.
-- 화면은 이 함수가 없을 때도 깨지지 않고 「아직 준비 중이에요」로 안내한다.

-- ---- 1. 표: 상황 칸 + 종류 'act' ----
alter table public.rel_records add column if not exists situation text;
alter table public.rel_records drop constraint if exists rel_records_situation_check;
alter table public.rel_records add constraint rel_records_situation_check
  check (situation is null or length(situation) <= 200);
alter table public.rel_records drop constraint if exists rel_records_kind_check;
alter table public.rel_records add constraint rel_records_kind_check
  check (kind = any (array['call','memo','gift','act']));

-- ---- 2. 먼저 한 행동 넣기 ----
create or replace function public.rel_add_act(p_token text, p_person uuid, p_date date, p_situation text, p_body text)
returns json language plpgsql security definer set search_path to 'public', 'extensions'
as $function$
declare w work_members := rel_member(p_token); p rel_people; nid uuid;
begin
  select * into p from rel_people where id=p_person and created_by=w.id;
  if p.id is null then return json_build_object('error','없는 사람이에요.'); end if;
  if length(btrim(coalesce(p_body,'')))=0 then return json_build_object('error','먼저 한 행동을 적어 주세요.'); end if;
  insert into rel_records(owner_id, created_by, person_id, area, kind, happened_at, situation, body)
  values (w.owner_id, w.id, p.id, coalesce(p.areas[1],'개인'), 'act',
          (coalesce(p_date, (now() at time zone 'Asia/Seoul')::date)::timestamp + time '12:00') at time zone 'Asia/Seoul',
          nullif(left(btrim(coalesce(p_situation,'')),200),''), left(btrim(p_body),500))
  returning id into nid;
  return json_build_object('id', nid);
end $function$;

-- ---- 3. 가져오기: 기록에 상황(sit)을 같이 준다 ----
create or replace function public.rel_sync(p_token text)
returns json language plpgsql stable security definer set search_path to 'public', 'extensions'
as $function$
declare w work_members := rel_member(p_token); mine text[];
begin
  select coalesce(array_agg(area order by created_at, area),'{}') into mine from rel_my_areas where work_member_id = w.id;
  return json_build_object(
    'me', json_build_object('name', w.name,
       'areas', to_json(coalesce(w.visible_companies,'{}') || mine),
       'private', to_json(mine)),
    'people', (select coalesce(json_agg(json_build_object(
         'id', p.id, 'name', p.name, 'phones', p.phones, 'mine', p.created_by=w.id,
         'areas', to_json(p.areas), 'profile', p.profile
       ) order by p.name),'[]')
       from rel_people p where p.created_by = w.id),
    'records', (select coalesce(json_agg(json_build_object(
         'id', r.id, 'person_id', r.person_id, 'area', r.area, 'kind', r.kind, 'dir', r.direction,
         'at', r.happened_at, 'dur', r.duration_s, 'body', r.body, 'sit', r.situation, 'mine', true, 'by', w.name,
         'note', r.note_id is not null
       ) order by r.happened_at desc),'[]')
       from rel_records r where r.created_by = w.id)
  );
end $function$;

-- ---- 4. 라이프 RPG 쪽 (2026-11-18 룡 OK) ----
-- 하루 셈(rpg_days): 선물·먼저 한 행동을 「통화」로 세지 않고 제 이름표로 센다.
-- 보고서 재료(rpg_report_data): 먼저 한 행동은 뺀다(남을 관찰한 글이라 AI 보고서에 넣지 않는다).
-- 지금 DB의 함수 본문에서 그 한 자리만 바꾼다(자리를 못 찾으면 멈춘다).
do $do$
declare d text;
begin
  d := pg_get_functiondef('public.rpg_days(text)'::regprocedure);
  if position($a$case when kind='memo' then '메모' else '통화' end$a$ in d) = 0 then raise exception 'rpg_days: 바꿀 자리를 못 찾음'; end if;
  execute replace(d, $a$case when kind='memo' then '메모' else '통화' end$a$,
                     $b$case kind when 'memo' then '메모' when 'gift' then '선물' when 'act' then '먼저 한 행동' else '통화' end$b$);
  d := pg_get_functiondef('public.rpg_report_data(uuid,date,date)'::regprocedure);
  if position($a$from rel_records r where r.created_by=p_member and r.happened_at::date$a$ in d) = 0 then raise exception 'rpg_report_data: 바꿀 자리를 못 찾음'; end if;
  execute replace(d, $a$from rel_records r where r.created_by=p_member and r.happened_at::date$a$,
                     $b$from rel_records r where r.created_by=p_member and r.kind <> 'act' and r.happened_at::date$b$);
end $do$;
