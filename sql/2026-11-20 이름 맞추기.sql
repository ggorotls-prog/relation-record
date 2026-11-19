-- 관계 기록: 이름 맞추기 (2026-11-20 룡)
-- 폰 연락처에서 이름을 바꾸면 관계 기록 이름도 같이 바뀌게, 이름만 바꾸는 함수.
-- (영역·번호는 건드리지 않는다. 15분 확인 작업은 영역을 모르므로 rel_save_person을 못 쓴다.)
-- 꼴은 기존 rel_ 함수와 같다: rel_member(p_token)로 본인 확인 · security definer · 결과는 json, 틀리면 {error}.
-- 내가 등록한 사람만 바꾼다(created_by = 본인).

create or replace function public.rel_rename_person(p_token text, p_id uuid, p_name text)
returns json language plpgsql security definer set search_path to 'public', 'extensions'
as $function$
declare w work_members := rel_member(p_token); n text := left(btrim(coalesce(p_name,'')), 60);
begin
  if length(n)=0 then return json_build_object('error','이름을 적어 주세요.'); end if;
  update rel_people set name=n, updated_at=now() where id=p_id and created_by=w.id;
  if not found then return json_build_object('error','없는 사람이에요.'); end if;
  return json_build_object('id', p_id);
end $function$;
