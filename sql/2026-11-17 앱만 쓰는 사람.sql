-- 관계 기록: 앱만 쓰는 사람이 혼자 쓸 수 있게 (2026-11-17)
-- 아직 적용하지 않았다. 슈파베이스 「룡룡룡」(trlnatdkebjvgiqneulv)에 그대로 돌리면 된다.
-- 꼴은 기존 rel_ 함수와 같다: rel_member(p_token)로 본인 확인 · security definer · search_path public, extensions
--   · 본인 것만(created_by = w.id / work_member_id = w.id) · 결과는 json, 틀리면 {error: 한국어 안내}.
-- 권한(grant)도 기존 rel_ 함수와 같게 따로 주지 않는다(기본값 = 누구나 실행, 열쇠 없으면 rel_member가 'auth'로 막는다).
-- 화면은 이 함수들이 없을 때도 깨지지 않고 「아직 준비 중이에요」로 안내한다.

-- ---- 1. 나만 보는 칸 ----

-- 칸 더하기
create or replace function public.rel_add_my_area(p_token text, p_area text)
returns json language plpgsql security definer set search_path to 'public', 'extensions'
as $function$
declare w work_members := rel_member(p_token); a text := btrim(coalesce(p_area,''));
begin
  if a = '' then return json_build_object('error','칸 이름을 적어 주세요.'); end if;
  if length(a) > 20 then return json_build_object('error','칸 이름은 20자까지예요.'); end if;
  if a = '개인' or a = '전체' or a = any(coalesce(w.visible_companies,'{}'))
     or exists (select 1 from rel_my_areas m where m.work_member_id = w.id and m.area = a) then
    return json_build_object('error','이미 있는 칸이에요.');
  end if;
  insert into rel_my_areas(work_member_id, area) values (w.id, a);
  return json_build_object('ok', true);
end $function$;

-- 칸 이름 바꾸기: 칸 · 그 칸에 든 사람 · 그 칸 기록을 한꺼번에(본인 것만)
create or replace function public.rel_rename_my_area(p_token text, p_old text, p_new text)
returns json language plpgsql security definer set search_path to 'public', 'extensions'
as $function$
declare w work_members := rel_member(p_token); o text := btrim(coalesce(p_old,'')); a text := btrim(coalesce(p_new,''));
begin
  if not exists (select 1 from rel_my_areas m where m.work_member_id = w.id and m.area = o) then
    return json_build_object('error','없는 칸이에요.');
  end if;
  if a = '' then return json_build_object('error','칸 이름을 적어 주세요.'); end if;
  if a = o then return json_build_object('ok', true); end if;
  if length(a) > 20 then return json_build_object('error','칸 이름은 20자까지예요.'); end if;
  if a = '개인' or a = '전체' or a = any(coalesce(w.visible_companies,'{}'))
     or exists (select 1 from rel_my_areas m where m.work_member_id = w.id and m.area = a) then
    return json_build_object('error','이미 있는 칸이에요.');
  end if;
  update rel_my_areas set area = a where work_member_id = w.id and area = o;
  update rel_people set areas = array_replace(areas, o, a), updated_at = now() where created_by = w.id and o = any(areas);
  update rel_records set area = a, updated_at = now() where created_by = w.id and area = o;
  return json_build_object('ok', true);
end $function$;

-- 칸 빼기: 그 칸에 사람이나 기록이 있으면 빼지 않고 안내
create or replace function public.rel_delete_my_area(p_token text, p_area text)
returns json language plpgsql security definer set search_path to 'public', 'extensions'
as $function$
declare w work_members := rel_member(p_token); n int;
begin
  select count(*) into n from rel_people where created_by = w.id and p_area = any(areas);
  if n > 0 then return json_build_object('error', '이 칸에 ' || n || '명이 있어요. 그 사람들을 다른 칸으로 옮긴 뒤 빼 주세요.'); end if;
  if exists (select 1 from rel_records where created_by = w.id and area = p_area) then
    return json_build_object('error','이 칸에 든 기록이 있어요. 기록을 다른 칸으로 옮긴 뒤 빼 주세요.');
  end if;
  delete from rel_my_areas where work_member_id = w.id and area = p_area;
  if not found then return json_build_object('error','없는 칸이에요.'); end if;
  return json_build_object('ok', true);
end $function$;

-- ---- 3. 두 사람 합치기 ----
-- p_drop의 통화·메모·선물·번호·영역·비어 있는 프로필 칸을 p_keep으로 옮기고 p_drop 줄을 지운다.
-- 함수 하나 = 한 트랜잭션(중간에 틀리면 모두 되돌아간다). 둘 다 본인이 등록한 사람이어야 한다.
create or replace function public.rel_merge_people(p_token text, p_keep uuid, p_drop uuid)
returns json language plpgsql security definer set search_path to 'public', 'extensions'
as $function$
declare w work_members := rel_member(p_token); k rel_people; d rel_people; pr jsonb;
begin
  if p_keep is null or p_drop is null or p_keep = p_drop then return json_build_object('error','합칠 사람을 골라 주세요.'); end if;
  select * into k from rel_people where id = p_keep and created_by = w.id for update;
  select * into d from rel_people where id = p_drop and created_by = w.id for update;
  if k.id is null or d.id is null then return json_build_object('error','없는 사람이에요.'); end if;

  -- 프로필: 남는 사람 것이 먼저, 비어 있는 칸만 지울 사람 것으로. 「그 밖에」는 둘 다 있으면 이어 붙인다
  pr := coalesce(k.profile,'{}');
  if not (pr ? 'birthday') and (d.profile ? 'birthday') then
    pr := pr || jsonb_strip_nulls(jsonb_build_object('birthday', d.profile->'birthday', 'lunar', d.profile->'lunar'));
  end if;
  if not (pr ? 'address') and (d.profile ? 'address') then pr := pr || jsonb_build_object('address', d.profile->'address'); end if;
  if (d.profile ? 'other') then
    pr := pr || jsonb_build_object('other', left(case when pr ? 'other' then (pr->>'other') || E'\n' || (d.profile->>'other') else d.profile->>'other' end, 1000));
  end if;

  update rel_records set person_id = k.id, updated_at = now() where person_id = d.id; -- 지울 사람의 기록은 하나도 남김없이(안 옮기면 지울 때 같이 지워진다)
  update rel_people set
    phones = k.phones || array(select x from unnest(d.phones) with ordinality u(x,o) where not (x = any(k.phones)) order by o), -- 남는 사람 번호 순서 그대로, 새 번호만 뒤에
    areas = k.areas || array(select x from unnest(d.areas) with ordinality u(x,o) where not (x = any(k.areas)) order by o),
    profile = pr,
    updated_at = now()
  where id = k.id;
  delete from rel_people where id = d.id;
  return json_build_object('id', k.id);
end $function$;
