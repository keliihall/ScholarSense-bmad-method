do $$ begin
    create role scholarsense_ingestion_quality_batch_owner nologin;
exception when duplicate_object then null;
end $$;
do $$ begin
    create role scholarsense_ingestion_quality_quality_worker nologin;
exception when duplicate_object then null;
end $$;
do $$ begin
    create role scholarsense_ingestion_quality_retention_executor nologin;
exception when duplicate_object then null;
end $$;
do $$ begin
    create role scholarsense_ingestion_quality_consumer_registry_authority nologin;
exception when duplicate_object then null;
end $$;

alter role scholarsense_ingestion_quality_batch_owner
    nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
alter role scholarsense_ingestion_quality_quality_worker
    nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
alter role scholarsense_ingestion_quality_retention_executor
    nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
alter role scholarsense_ingestion_quality_consumer_registry_authority
    nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;

create function ingestion_quality.iq_utf8_scalar_count(value bytea)
returns integer
language plpgsql
immutable
strict
set search_path = pg_catalog
as $$
declare
    iq_offset integer := 0;
    iq_length integer := octet_length(value);
    iq_count integer := 0;
    iq_first integer;
    iq_second integer;
    iq_third integer;
    iq_fourth integer;
begin
    while iq_offset < iq_length loop
        iq_first := get_byte(value, iq_offset);
        if iq_first <= 127 then
            iq_offset := iq_offset + 1;
        elsif iq_first between 194 and 223 then
            if iq_offset + 1 >= iq_length then return -1; end if;
            iq_second := get_byte(value, iq_offset + 1);
            if iq_second not between 128 and 191 then return -1; end if;
            iq_offset := iq_offset + 2;
        elsif iq_first between 224 and 239 then
            if iq_offset + 2 >= iq_length then return -1; end if;
            iq_second := get_byte(value, iq_offset + 1);
            iq_third := get_byte(value, iq_offset + 2);
            if iq_third not between 128 and 191
               or (iq_first = 224 and iq_second not between 160 and 191)
               or (iq_first = 237 and iq_second not between 128 and 159)
               or (iq_first not in (224, 237) and iq_second not between 128 and 191) then
                return -1;
            end if;
            iq_offset := iq_offset + 3;
        elsif iq_first between 240 and 244 then
            if iq_offset + 3 >= iq_length then return -1; end if;
            iq_second := get_byte(value, iq_offset + 1);
            iq_third := get_byte(value, iq_offset + 2);
            iq_fourth := get_byte(value, iq_offset + 3);
            if iq_third not between 128 and 191
               or iq_fourth not between 128 and 191
               or (iq_first = 240 and iq_second not between 144 and 191)
               or (iq_first = 244 and iq_second not between 128 and 143)
               or (iq_first not in (240, 244) and iq_second not between 128 and 191) then
                return -1;
            end if;
            iq_offset := iq_offset + 4;
        else
            return -1;
        end if;
        iq_count := iq_count + 1;
    end loop;
    return iq_count;
end
$$;

create function ingestion_quality.iq_json_exact_object(
    input_value jsonb,
    expected_keys text[])
returns boolean
language sql
immutable
strict
set search_path = pg_catalog
as $$
select jsonb_typeof(input_value) = 'object'
   and (select count(*) from jsonb_object_keys(input_value)) = cardinality(expected_keys)
   and input_value ?& expected_keys
$$;

create function ingestion_quality.iq_json_quote_utf8(input_value bytea)
returns text
language plpgsql
immutable
strict
set search_path = pg_catalog
as $$
declare
    iq_result text := '"';
    iq_offset integer := 0;
    iq_length integer := octet_length(input_value);
    iq_first integer;
    iq_second integer;
    iq_third integer;
    iq_fourth integer;
    iq_width integer;
begin
    while iq_offset < iq_length loop
        iq_first := get_byte(input_value, iq_offset);
        if iq_first < 128 then
            if iq_first = 34 then
                iq_result := iq_result || chr(92) || '"';
            elsif iq_first = 92 then
                iq_result := iq_result || chr(92) || chr(92);
            elsif iq_first = 8 then
                iq_result := iq_result || chr(92) || 'b';
            elsif iq_first = 9 then
                iq_result := iq_result || chr(92) || 't';
            elsif iq_first = 10 then
                iq_result := iq_result || chr(92) || 'n';
            elsif iq_first = 12 then
                iq_result := iq_result || chr(92) || 'f';
            elsif iq_first = 13 then
                iq_result := iq_result || chr(92) || 'r';
            elsif iq_first < 32 then
                iq_result := iq_result || chr(92) || 'u00'
                    || lpad(to_hex(iq_first), 2, '0');
            else
                iq_result := iq_result || chr(iq_first);
            end if;
            iq_offset := iq_offset + 1;
            continue;
        end if;
        if iq_first between 194 and 223 then
            iq_width := 2;
        elsif iq_first between 224 and 239 then
            iq_width := 3;
        elsif iq_first between 240 and 244 then
            iq_width := 4;
        else
            raise exception using
                errcode = 'character_not_in_repertoire',
                message = 'INGESTION_QUALITY_CANONICAL_UTF8_INVALID';
        end if;
        if iq_offset + iq_width > iq_length then
            raise exception using
                errcode = 'character_not_in_repertoire',
                message = 'INGESTION_QUALITY_CANONICAL_UTF8_INVALID';
        end if;
        iq_second := get_byte(input_value, iq_offset + 1);
        if iq_second not between 128 and 191 then
            raise exception using
                errcode = 'character_not_in_repertoire',
                message = 'INGESTION_QUALITY_CANONICAL_UTF8_INVALID';
        end if;
        if iq_width >= 3 then
            iq_third := get_byte(input_value, iq_offset + 2);
            if iq_third not between 128 and 191 then
                raise exception using
                    errcode = 'character_not_in_repertoire',
                    message = 'INGESTION_QUALITY_CANONICAL_UTF8_INVALID';
            end if;
        end if;
        if iq_width = 4 then
            iq_fourth := get_byte(input_value, iq_offset + 3);
            if iq_fourth not between 128 and 191 then
                raise exception using
                    errcode = 'character_not_in_repertoire',
                    message = 'INGESTION_QUALITY_CANONICAL_UTF8_INVALID';
            end if;
        end if;
        if (iq_first = 224 and iq_second < 160)
           or (iq_first = 237 and iq_second > 159)
           or (iq_first = 240 and iq_second < 144)
           or (iq_first = 244 and iq_second > 143) then
            raise exception using
                errcode = 'character_not_in_repertoire',
                message = 'INGESTION_QUALITY_CANONICAL_UTF8_INVALID';
        end if;
        iq_result := iq_result || convert_from(
            substring(input_value from iq_offset + 1 for iq_width), 'UTF8');
        iq_offset := iq_offset + iq_width;
    end loop;
    return iq_result || '"';
end
$$;

create function ingestion_quality.iq_json_canonical(input_value jsonb)
returns text
language plpgsql
immutable
strict
set search_path = pg_catalog
as $$
declare
    iq_type text := jsonb_typeof(input_value);
    iq_result text;
    iq_number text;
begin
    case iq_type
      when 'object' then
        select '{' || coalesce(string_agg(
            ingestion_quality.iq_json_quote_utf8(convert_to(iq_key, 'UTF8')) || ':'
              || ingestion_quality.iq_json_canonical(iq_value),
            ',' order by iq_key collate "C"), '') || '}'
          into iq_result
          from jsonb_each(input_value) entry(iq_key, iq_value);
        return iq_result;
      when 'array' then
        select '[' || coalesce(string_agg(
            ingestion_quality.iq_json_canonical(iq_value),
            ',' order by iq_ordinal), '') || ']'
          into iq_result
          from jsonb_array_elements(input_value)
            with ordinality entry(iq_value, iq_ordinal);
        return iq_result;
      when 'string' then
        return ingestion_quality.iq_json_quote_utf8(
            convert_to(input_value #>> '{}', 'UTF8'));
      when 'number' then
        iq_number := input_value::text;
        if iq_number !~ '^-?(0|[1-9][0-9]*)$'
           or abs(iq_number::numeric) > 9007199254740991 then
            raise exception using
                errcode = 'numeric_value_out_of_range',
                message = 'INGESTION_QUALITY_CANONICAL_INTEGER_INVALID';
        end if;
        return iq_number;
      when 'boolean' then return input_value::text;
      when 'null' then return 'null';
      else
        raise exception using
            errcode = 'invalid_parameter_value',
            message = 'INGESTION_QUALITY_CANONICAL_JSON_INVALID';
    end case;
end
$$;

create function ingestion_quality.iq_qshm_canonical(input_value jsonb)
returns text
language plpgsql
immutable
strict
set search_path = pg_catalog
as $$
declare
    iq_type text := jsonb_typeof(input_value);
    iq_result text;
    iq_number text;
    iq_hex text;
begin
    case iq_type
      when 'object' then
        if input_value ? '$utf8' then
            if not ingestion_quality.iq_json_exact_object(input_value, array['$utf8'])
               or jsonb_typeof(input_value -> '$utf8') <> 'string' then
                raise exception using
                    errcode = 'invalid_parameter_value',
                    message = 'INGESTION_QUALITY_QSHM_UTF8_WRAPPER_INVALID';
            end if;
            iq_hex := input_value ->> '$utf8';
            if iq_hex !~ '^([0-9a-f]{2})*$' then
                raise exception using
                    errcode = 'invalid_parameter_value',
                    message = 'INGESTION_QUALITY_QSHM_UTF8_WRAPPER_INVALID';
            end if;
            return ingestion_quality.iq_json_quote_utf8(decode(iq_hex, 'hex'));
        end if;
        select '{' || coalesce(string_agg(
            ingestion_quality.iq_json_quote_utf8(convert_to(iq_key, 'UTF8')) || ':'
              || ingestion_quality.iq_qshm_canonical(iq_value),
            ',' order by iq_key collate "C"), '') || '}'
          into iq_result
          from jsonb_each(input_value) entry(iq_key, iq_value);
        return iq_result;
      when 'array' then
        select '[' || coalesce(string_agg(
            ingestion_quality.iq_qshm_canonical(iq_value),
            ',' order by iq_ordinal), '') || ']'
          into iq_result
          from jsonb_array_elements(input_value)
            with ordinality entry(iq_value, iq_ordinal);
        return iq_result;
      when 'string' then
        return ingestion_quality.iq_json_quote_utf8(
            convert_to(input_value #>> '{}', 'UTF8'));
      when 'number' then
        iq_number := input_value::text;
        if iq_number !~ '^(0|[1-9][0-9]*)$'
           or iq_number::numeric > 9007199254740991 then
            raise exception using
                errcode = 'numeric_value_out_of_range',
                message = 'INGESTION_QUALITY_QSHM_INTEGER_INVALID';
        end if;
        return iq_number;
      when 'boolean' then return input_value::text;
      when 'null' then return 'null';
      else
        raise exception using
            errcode = 'invalid_parameter_value',
            message = 'INGESTION_QUALITY_QSHM_JSON_INVALID';
    end case;
end
$$;

create function ingestion_quality.iq_canonical_instant(input_value timestamptz)
returns text
language sql
immutable
strict
set search_path = pg_catalog
as $$
select to_char(input_value at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
$$;

create table ingestion_quality.iq_frozen_qmdp_policy (
    singleton boolean primary key default true check (singleton),
    profile_version varchar(64) not null unique check (
        profile_version = 'QMDP-1.0.0'),
    raw_digest char(71) not null check (
        raw_digest = 'sha256:1e7703748a7bda56034ab189700a82a503674d364ecac67c1eb35fe6ee8c0d84'),
    canonical_digest char(71) not null check (
        canonical_digest = 'sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8'),
    raw_utf8 bytea not null,
    policy jsonb not null
);

with iq_raw(value) as (
  select decode('ewogICIkc2NoZW1hIjogImV4ZWN1dGFibGUtcXVhbGl0eS1wb2xpY3kuc2NoZW1hLmpzb24iLAogICJwcm9maWxlVmVyc2lvbiI6ICJRTURQLTEuMC4wIiwKICAiZGVjaXNpb25JZCI6ICJERUMtMDE5IiwKICAiYXV0aG9yaXR5UmVmIjogIkFVVEgtMjAyNi0wOC0wOC0wMDEiLAogICJhcHByb3ZhbFJlZiI6ICJBVVRILTIwMjYtMDgtMDgtMDAxIiwKICAiYXBwcm92ZWRCeSI6ICJIZWkiLAogICJhcHByb3ZlZEF0IjogIjIwMjYtMDgtMDlUMTA6MDI6MjIrMDg6MDAiLAogICJlZmZlY3RpdmVBdCI6ICIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwKICAib3duZXIiOiAiSGVpIiwKICAiZXZpZGVuY2VSZWYiOiAic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwKICAic3RhdHVzIjogImFwcHJvdmVkIiwKICAiY2Fub25pY2FsaXphdGlvbiI6IHsKICAgICJwcm9maWxlIjogIlNDSE9MQVJTRU5TRS1DQU5PTklDQUwtSlNPTi0xLjAuMCIsCiAgICAiZW5jb2RpbmciOiAiVVRGLTgiLAogICAgIm9iamVjdEtleU9yZGVyIjogImxleGljb2dyYXBoaWMiLAogICAgImR1cGxpY2F0ZUtleXMiOiAicmVqZWN0IiwKICAgICJiaW5hcnlGbG9hdCI6ICJmb3JiaWRkZW4iLAogICAgInRpbWUiOiAiSVNPLTg2MDEtb2Zmc2V0IiwKICAgICJkaWdlc3RBbGdvcml0aG0iOiAic2hhMjU2IiwKICAgICJkaWdlc3RQcmVmaXgiOiAic2hhMjU2OiIKICB9LAogICJudW1lcmljU2VtYW50aWNzIjogewogICAgInJlcHJlc2VudGF0aW9uIjogIm5vbi1uZWdhdGl2ZS1pbnRlZ2VyLXJhdGlvbmFsIiwKICAgICJiYXNpc1BvaW50U2NhbGUiOiAxMDAwMCwKICAgICJ2YWx1ZVNjYWxlIjogMCwKICAgICJyb3VuZGluZ01vZGUiOiAiSEFMRl9VUCIsCiAgICAiY29tcGFyaXNvblN0YWdlIjogInByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsCiAgICAiemVyb0Rlbm9taW5hdG9yIjogImV2YWx1YXRpb24tZXJyb3IvUVVBTElUWV9QT0xJQ1lfWkVST19ERU5PTUlOQVRPUiIKICB9LAogICJ3aW5kb3dTZW1hbnRpY3MiOiB7CiAgICAiaW50ZXJ2YWwiOiAiW3dpbmRvd1N0YXJ0QXQsd2luZG93RW5kQXQpIiwKICAgICJzdG9yYWdlVGltZXpvbmUiOiAiVVRDIiwKICAgICJzY2hlZHVsZVRpbWV6b25lIjogIkFzaWEvU2hhbmdoYWkiLAogICAgImZyZXNobmVzc1dpbmRvd0hvdXJzIjogNzIwLAogICAgImN1dG9mZkJvdW5kYXJ5IjogImluY2x1c2l2ZSIKICB9LAogICJmcmVzaG5lc3NNYW5pZmVzdEJpbmRpbmciOiB7CiAgICAic291cmNlT2NjdXJyZWRBdCI6ICJtYW5pZmVzdC5zb3VyY2VPY2N1cnJlZEF0IiwKICAgICJzY2hlZHVsZWREdWVBdCI6ICJtYW5pZmVzdC5zY2hlZHVsZWREdWVBdCIsCiAgICAicmVjZWl2ZWRBdCI6ICJtYW5pZmVzdC5yZWNlaXZlZEF0IiwKICAgICJsYW5lSWQiOiAibWFuaWZlc3QubGFuZUlkIgogIH0sCiAgImNvbnRyb2xsZWRJbnB1dHMiOiB7CiAgICAiZGF0YUNhdGFsb2ciOiB7InBhdGgiOiJjb250cmFjdHMvZGF0YS1jYXRhbG9nL2RjYy0xLjEuMC5qc29uIiwidmVyc2lvbiI6IkRDQy0xLjEuMCIsInJhd1NoYTI1NiI6ImFlYjE5OTYyMDcxZTIxNDRhODViYjJlMDdmZDEyNGVmZjBkNjllZGY5M2Y3MjAyZTgyMTIwNDYxNmE2MGYyMTkiLCJjYW5vbmljYWxEaWdlc3QiOiJzaGEyNTY6MTU2ZGY1MmEzNThhY2ZiMjFmNzZiYzc0ZmRjZGMyMDRkNWI3YTliZjkxMTQ4YzAwOTA3NDY1NmM0OTE2YzY1ZCJ9LAogICAgInF1YWxpdHlHYXRlIjogeyJwYXRoIjoiY29udHJhY3RzL2RhdGEtY2F0YWxvZy9xZy0xLjAuMC5qc29uIiwidmVyc2lvbiI6IlFHLTEuMC4wIiwicmF3U2hhMjU2IjoiMTc4OWM2MDk5ZWYzYTQ4Yjg3NzE0Mzk0YzkwMzQ0OWM4Zjc1YjljZTkyY2JhMzAwZjc1MmRhMzNmN2UyYmNlZCIsImNhbm9uaWNhbERpZ2VzdCI6InNoYTI1NjowYTY3MDFjYzU5OGJlNzU0ODEzYmRkZGJlN2UwY2JmZjdiNjVjMWUwM2NhMmNkMDI2ZDYzZWUyZDM2NmE0ZTNhIn0KICB9LAogICJjb21tb25NZXRyaWNzIjogWwogICAgeyJkZWZpbml0aW9uS2luZCI6ImNvbW1vbiIsIm1ldHJpY0lkIjoiUFJJTUFSWV9LRVlfQ09NUExFVEVORVNTX0JQIiwiZm9ybXVsYUlkIjoiUU1EUC0xLjAuMC9QUklNQVJZX0tFWV9DT01QTEVURU5FU1NfQlAiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJwcmltYXJ5LWtleSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJtYW5pZmVzdC1yZWNvcmRzLXdpdGgtY29tcGxldGUtdmFsaWQtYnVzaW5lc3Mta2V5In0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6Im1hbmlmZXN0LWRlY2xhcmVkLXJlY29yZC1jb3VudCJ9fSwidW5pdCI6ImJhc2lzLXBvaW50Iiwib3BlcmF0b3IiOiI+PSIsImJvdW5kYXJ5IjoiaW5jbHVzaXZlIiwidGhyZXNob2xkTnVtZXJhdG9yIjo5OTUwLCJ0aHJlc2hvbGREZW5vbWluYXRvciI6MTAwMDAsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoiYWx3YXlzIn0sIm93bmVyIjoiSGVpIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0sCiAgICB7ImRlZmluaXRpb25LaW5kIjoiY29tbW9uIiwibWV0cmljSWQiOiJQMF9TVUJKRUNUX01BUFBJTkdfQlAiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL1AwX1NVQkpFQ1RfTUFQUElOR19CUCIsImZvcm11bGFWZXJzaW9uIjoiMS4wLjAiLCJjYXRlZ29yeSI6InByaW1hcnkta2V5IiwiY2FsY3VsYXRpb24iOnsia2luZCI6InJhdGlvIiwibnVtZXJhdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6InAwLXJlY29yZHMtd2l0aC11bmlxdWUtc3R1ZGVudC1yZWYifSwiZGVub21pbmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoicDAtcmVjb3Jkcy1yZXF1aXJpbmctc3ViamVjdC1tYXBwaW5nIn19LCJ1bml0IjoiYmFzaXMtcG9pbnQiLCJvcGVyYXRvciI6Ij49IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjk5NTAsInRocmVzaG9sZERlbm9taW5hdG9yIjoxMDAwMCwiZGVub21pbmF0b3JaZXJvQmVoYXZpb3IiOiJldmFsdWF0aW9uLWVycm9yIiwidmFsdWVTY2FsZSI6MCwicm91bmRpbmdNb2RlIjoiSEFMRl9VUCIsImNvbXBhcmlzb25TdGFnZSI6InByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsImFwcGxpY2FiaWxpdHkiOnsicHJlZGljYXRlSWQiOiJzb3VyY2UtaW4tYXBwcm92ZWQtc2V0Iiwic291cmNlSWRzIjpbIlNSQy1QMC1TVFVERU5ULTAwMSIsIlNSQy1QMC1BQ0NPTU1PREFUSU9OLTAwMSIsIlNSQy1QMC1DQVJELTAwMSIsIlNSQy1QMC1DQU1QVVMtQUNDRVNTLTAwMSIsIlNSQy1QMC1ET1JNLUFDQ0VTUy0wMDEiLCJTUkMtUDAtTEVBVkUtMDAxIiwiU1JDLVAwLVRJTUVUQUJMRS0wMDEiXX0sIm93bmVyIjoiSGVpIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0sCiAgICB7ImRlZmluaXRpb25LaW5kIjoiY29tbW9uIiwibWV0cmljSWQiOiJSRVFVSVJFRF9GSUVMRF9WQUxJRElUWV9CUCIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvUkVRVUlSRURfRklFTERfVkFMSURJVFlfQlAiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb3ZlcmFnZSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJyZWNvcmRzLXdpdGgtdmFsaWQtcmVxdWlyZWQtZmllbGQtc2V0In0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6Im1hbmlmZXN0LWRlY2xhcmVkLXJlY29yZC1jb3VudCJ9fSwidW5pdCI6ImJhc2lzLXBvaW50Iiwib3BlcmF0b3IiOiI9IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjEsInRocmVzaG9sZERlbm9taW5hdG9yIjoxLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6ImFsd2F5cyJ9LCJvd25lciI6IkhlaSIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9LAogICAgeyJkZWZpbml0aW9uS2luZCI6ImNvbW1vbiIsIm1ldHJpY0lkIjoiVkFMSURfUkVDT1JEX1JBVEVfQlAiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL1ZBTElEX1JFQ09SRF9SQVRFX0JQIiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY292ZXJhZ2UiLCJjYWxjdWxhdGlvbiI6eyJraW5kIjoicmF0aW8iLCJudW1lcmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoicmVjb3Jkcy1wYXNzaW5nLXNjaGVtYS1pbnRlcnZhbC1wdXJwb3NlIn0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6Im1hbmlmZXN0LWRlY2xhcmVkLXJlY29yZC1jb3VudCJ9fSwidW5pdCI6ImJhc2lzLXBvaW50Iiwib3BlcmF0b3IiOiI+PSIsImJvdW5kYXJ5IjoiaW5jbHVzaXZlIiwidGhyZXNob2xkTnVtZXJhdG9yIjo5OTUwLCJ0aHJlc2hvbGREZW5vbWluYXRvciI6MTAwMDAsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoiYWx3YXlzIn0sIm93bmVyIjoiSGVpIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0sCiAgICB7ImRlZmluaXRpb25LaW5kIjoiY29tbW9uIiwibWV0cmljSWQiOiJDT1JFX0ZJRUxEX0NPVkVSQUdFX0JQIiwiZm9ybXVsYUlkIjoiUU1EUC0xLjAuMC9DT1JFX0ZJRUxEX0NPVkVSQUdFX0JQIiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY292ZXJhZ2UiLCJjYWxjdWxhdGlvbiI6eyJraW5kIjoicmF0aW8iLCJudW1lcmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoidmFsaWQtY29yZS1maWVsZC1jZWxscyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJleHBlY3RlZC1jb3JlLWZpZWxkLWNlbGxzIn19LCJ1bml0IjoiYmFzaXMtcG9pbnQiLCJvcGVyYXRvciI6Ij49IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjk4MDAsInRocmVzaG9sZERlbm9taW5hdG9yIjoxMDAwMCwiZGVub21pbmF0b3JaZXJvQmVoYXZpb3IiOiJldmFsdWF0aW9uLWVycm9yIiwidmFsdWVTY2FsZSI6MCwicm91bmRpbmdNb2RlIjoiSEFMRl9VUCIsImNvbXBhcmlzb25TdGFnZSI6InByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsImFwcGxpY2FiaWxpdHkiOnsicHJlZGljYXRlSWQiOiJzb3VyY2UtZmllbGQtZ3JvdXAtcHJlc2VudCJ9LCJvd25lciI6IkhlaSIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9LAogICAgeyJkZWZpbml0aW9uS2luZCI6ImNvbW1vbiIsIm1ldHJpY0lkIjoiRlJFU0hORVNTX1dJVEhJTl9TTE9fQlAiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL0ZSRVNITkVTU19XSVRISU5fU0xPX0JQIiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiZnJlc2huZXNzIiwiY2FsY3VsYXRpb24iOnsia2luZCI6InJhdGlvIiwibnVtZXJhdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6Im9uLXRpbWUtZGVsaXZlcnktdW5pdHMifSwiZGVub21pbmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoiZXhwZWN0ZWQtZGVsaXZlcnktdW5pdHMifX0sInVuaXQiOiJiYXNpcy1wb2ludCIsIm9wZXJhdG9yIjoiPj0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6OTkwMCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEwMDAwLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6ImFsd2F5cyJ9LCJvd25lciI6IkhlaSIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9LAogICAgeyJkZWZpbml0aW9uS2luZCI6ImNvbW1vbiIsIm1ldHJpY0lkIjoiVU5SRVNPTFZFRF9JTlRFUlZBTF9DT05GTElDVF9DT1VOVCIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvVU5SRVNPTFZFRF9JTlRFUlZBTF9DT05GTElDVF9DT1VOVCIsImZvcm11bGFWZXJzaW9uIjoiMS4wLjAiLCJjYXRlZ29yeSI6ImNvbnRpbnVpdHkiLCJjYWxjdWxhdGlvbiI6eyJraW5kIjoiY291bnQiLCJudW1lcmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoidW5yZXNvbHZlZC1pbnRlcnZhbC1jb25mbGljdHMifSwiZGVub21pbmF0b3IiOnsia2luZCI6ImNvbnN0YW50IiwidmFsdWUiOjF9fSwidW5pdCI6ImNvdW50Iiwib3BlcmF0b3IiOiI9IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjAsInRocmVzaG9sZERlbm9taW5hdG9yIjoxLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6ImFsd2F5cyJ9LCJvd25lciI6IkhlaSIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9LAogICAgeyJkZWZpbml0aW9uS2luZCI6ImNvbW1vbiIsIm1ldHJpY0lkIjoiRFVQTElDQVRFX0JVU0lORVNTX0tFWV9DT1VOVCIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvRFVQTElDQVRFX0JVU0lORVNTX0tFWV9DT1VOVCIsImZvcm11bGFWZXJzaW9uIjoiMS4wLjAiLCJjYXRlZ29yeSI6ImNvbnRpbnVpdHkiLCJjYWxjdWxhdGlvbiI6eyJraW5kIjoiY291bnQiLCJudW1lcmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoiZHVwbGljYXRlLWJ1c2luZXNzLWtleXMifSwiZGVub21pbmF0b3IiOnsia2luZCI6ImNvbnN0YW50IiwidmFsdWUiOjF9fSwidW5pdCI6ImNvdW50Iiwib3BlcmF0b3IiOiI9IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjAsInRocmVzaG9sZERlbm9taW5hdG9yIjoxLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6ImFsd2F5cyJ9LCJvd25lciI6IkhlaSIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9LAogICAgeyJkZWZpbml0aW9uS2luZCI6ImNvbW1vbiIsIm1ldHJpY0lkIjoiVkVSU0lPTl9SRUdSRVNTSU9OX0NPVU5UIiwiZm9ybXVsYUlkIjoiUU1EUC0xLjAuMC9WRVJTSU9OX1JFR1JFU1NJT05fQ09VTlQiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb250aW51aXR5IiwiY2FsY3VsYXRpb24iOnsia2luZCI6ImNvdW50IiwibnVtZXJhdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6InZlcnNpb24tcmVncmVzc2lvbnMifSwiZGVub21pbmF0b3IiOnsia2luZCI6ImNvbnN0YW50IiwidmFsdWUiOjF9fSwidW5pdCI6ImNvdW50Iiwib3BlcmF0b3IiOiI9IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjAsInRocmVzaG9sZERlbm9taW5hdG9yIjoxLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6ImFsd2F5cyJ9LCJvd25lciI6IkhlaSIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9LAogICAgeyJkZWZpbml0aW9uS2luZCI6ImNvbW1vbiIsIm1ldHJpY0lkIjoiU09VUkNFX0NPTlRJTlVJVFlfR0FURSIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU09VUkNFX0NPTlRJTlVJVFlfR0FURSIsImZvcm11bGFWZXJzaW9uIjoiMS4wLjAiLCJjYXRlZ29yeSI6ImNvbnRpbnVpdHkiLCJjYWxjdWxhdGlvbiI6eyJraW5kIjoiY29tcG9zaXRlLWFuZCIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJwYXNzaW5nLW1lbWJlci1jb3VudCJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJhcHBsaWNhYmxlLW1lbWJlci1jb3VudCJ9fSwidW5pdCI6Im1lbWJlci1jb3VudCIsIm9wZXJhdG9yIjoiPSIsImJvdW5kYXJ5IjoiaW5jbHVzaXZlIiwidGhyZXNob2xkTnVtZXJhdG9yIjoxLCJ0aHJlc2hvbGREZW5vbWluYXRvciI6MSwiZGVub21pbmF0b3JaZXJvQmVoYXZpb3IiOiJldmFsdWF0aW9uLWVycm9yIiwidmFsdWVTY2FsZSI6MCwicm91bmRpbmdNb2RlIjoiSEFMRl9VUCIsImNvbXBhcmlzb25TdGFnZSI6InByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsImFwcGxpY2FiaWxpdHkiOnsicHJlZGljYXRlSWQiOiJhbHdheXMifSwib3duZXIiOiJIZWkiLCJhcHByb3ZhbFJlZiI6IkFVVEgtMjAyNi0wOC0wOC0wMDEiLCJlZmZlY3RpdmVBdCI6IjIwMjYtMDgtMDlUMTA6MDI6MjIrMDg6MDAiLCJldmlkZW5jZVJlZiI6InN0b3J5Oi8vMi4zL0RFQy0wMTkvQVVUSC0yMDI2LTA4LTA4LTAwMSIsImhhcmRHYXRlIjp0cnVlfSwKICAgIHsiZGVmaW5pdGlvbktpbmQiOiJjb21tb24iLCJtZXRyaWNJZCI6IlNDSEVNQV9BTExPV0xJU1RfQ09NUEFUSUJJTElUWV9CUCIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU0NIRU1BX0FMTE9XTElTVF9DT01QQVRJQklMSVRZX0JQIiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY29tcGF0aWJpbGl0eSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJzY2hlbWEtYWxsb3dsaXN0LWNvbXBhdGlibGUtcmVjb3JkcyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJtYW5pZmVzdC1kZWNsYXJlZC1yZWNvcmQtY291bnQifX0sInVuaXQiOiJiYXNpcy1wb2ludCIsIm9wZXJhdG9yIjoiPSIsImJvdW5kYXJ5IjoiaW5jbHVzaXZlIiwidGhyZXNob2xkTnVtZXJhdG9yIjoxLCJ0aHJlc2hvbGREZW5vbWluYXRvciI6MSwiZGVub21pbmF0b3JaZXJvQmVoYXZpb3IiOiJldmFsdWF0aW9uLWVycm9yIiwidmFsdWVTY2FsZSI6MCwicm91bmRpbmdNb2RlIjoiSEFMRl9VUCIsImNvbXBhcmlzb25TdGFnZSI6InByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsImFwcGxpY2FiaWxpdHkiOnsicHJlZGljYXRlSWQiOiJhbHdheXMifSwib3duZXIiOiJIZWkiLCJhcHByb3ZhbFJlZiI6IkFVVEgtMjAyNi0wOC0wOC0wMDEiLCJlZmZlY3RpdmVBdCI6IjIwMjYtMDgtMDlUMTA6MDI6MjIrMDg6MDAiLCJldmlkZW5jZVJlZiI6InN0b3J5Oi8vMi4zL0RFQy0wMTkvQVVUSC0yMDI2LTA4LTA4LTAwMSIsImhhcmRHYXRlIjp0cnVlfSwKICAgIHsiZGVmaW5pdGlvbktpbmQiOiJjb21tb24iLCJtZXRyaWNJZCI6IkZPUkJJRERFTl9GSUVMRF9DT1VOVCIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvRk9SQklEREVOX0ZJRUxEX0NPVU5UIiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoicHJpdmFjeSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJjb3VudCIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJmb3JiaWRkZW4tZmllbGQtaGl0cyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoiY29uc3RhbnQiLCJ2YWx1ZSI6MX19LCJ1bml0IjoiY291bnQiLCJvcGVyYXRvciI6Ij0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoiYWx3YXlzIn0sIm93bmVyIjoiSGVpIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0KICBdLAogICJzb3VyY2VzIjogWwogICAgewogICAgICAic291cmNlSWQiOiJTUkMtUDAtU1RVREVOVC0wMDEiLCJvd25lciI6IuWtpuexjeS4u+aVsOaNriBvd25lciIsInNjaGVtYUJpbmRpbmciOnsicGF0aCI6ImNvbnRyYWN0cy9kYXRhLWNhdGFsb2cvc291cmNlcy9zcmMtcDAtc3R1ZGVudC0wMDEuc2NoZW1hLmpzb24iLCJ2ZXJzaW9uIjoiU1RVREVOVC1TTElDRS0xLjAuMCIsInJhd1NoYTI1NiI6ImYxNDRiM2JkZjkzYmMxNTgyZmIxMmU1MDUxZDdmODcyMGNiOThmZjY5ZjQ3ZGMzZjUwZTNmZjMyODAyNzI1OGUiLCJjYW5vbmljYWxEaWdlc3QiOiJzaGEyNTY6ZDY2NWM5NDczNmRlZDlkZDBiNGVjMTg1Y2UxZTIwMzdhNDFhNTZkYmE1ZWIyNDY2MWE1MDdlYmYwMGUxNzkzYyJ9LAogICAgICAiYXBwbGljYWJsZUNvbW1vbk1ldHJpY0lkcyI6WyJQUklNQVJZX0tFWV9DT01QTEVURU5FU1NfQlAiLCJQMF9TVUJKRUNUX01BUFBJTkdfQlAiLCJSRVFVSVJFRF9GSUVMRF9WQUxJRElUWV9CUCIsIlZBTElEX1JFQ09SRF9SQVRFX0JQIiwiQ09SRV9GSUVMRF9DT1ZFUkFHRV9CUCIsIkZSRVNITkVTU19XSVRISU5fU0xPX0JQIiwiVU5SRVNPTFZFRF9JTlRFUlZBTF9DT05GTElDVF9DT1VOVCIsIkRVUExJQ0FURV9CVVNJTkVTU19LRVlfQ09VTlQiLCJWRVJTSU9OX1JFR1JFU1NJT05fQ09VTlQiLCJTT1VSQ0VfQ09OVElOVUlUWV9HQVRFIiwiU0NIRU1BX0FMTE9XTElTVF9DT01QQVRJQklMSVRZX0JQIiwiRk9SQklEREVOX0ZJRUxEX0NPVU5UIl0sCiAgICAgICJzb3VyY2VHYXRlcyI6WwogICAgICAgIHsiZGVmaW5pdGlvbktpbmQiOiJzb3VyY2UtZ2F0ZSIsIm1ldHJpY0lkIjoiQ09SRV9GSUVMRF9DT1ZFUkFHRV9CUCIsInNvdXJjZUlkIjoiU1JDLVAwLVNUVURFTlQtMDAxIiwiZ2F0ZUlkIjoic3R1ZGVudC1jb3JlLWZpZWxkLWdyb3VwIiwiZm9ybXVsYUlkIjoiUU1EUC0xLjAuMC9TUkMtUDAtU1RVREVOVC0wMDEvc3R1ZGVudC1jb3JlLWZpZWxkLWdyb3VwIiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY292ZXJhZ2UiLCJjYWxjdWxhdGlvbiI6eyJraW5kIjoicmF0aW8iLCJudW1lcmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoicmVjb3Jkcy1wYXNzaW5nLXNvdXJjZS1maWVsZC1ncm91cCJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJyZWNvcmRzLWFwcGxpY2FibGUtdG8tc291cmNlLWZpZWxkLWdyb3VwIn19LCJ1bml0IjoiYmFzaXMtcG9pbnQiLCJvcGVyYXRvciI6Ij49IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjk5NTAsInRocmVzaG9sZERlbm9taW5hdG9yIjoxMDAwMCwiZGVub21pbmF0b3JaZXJvQmVoYXZpb3IiOiJldmFsdWF0aW9uLWVycm9yIiwidmFsdWVTY2FsZSI6MCwicm91bmRpbmdNb2RlIjoiSEFMRl9VUCIsImNvbXBhcmlzb25TdGFnZSI6InByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsImFwcGxpY2FiaWxpdHkiOnsicHJlZGljYXRlSWQiOiJzb3VyY2UtZmllbGQtZ3JvdXAtcHJlc2VudCJ9LCJmaWVsZFNldCI6WyJzdHVkZW50UmVmIiwiZWZmZWN0aXZlRnJvbSIsImVmZmVjdGl2ZVRvIl0sIm93bmVyIjoi5a2m57GN5Li75pWw5o2uIG93bmVyIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0sCiAgICAgICAgeyJkZWZpbml0aW9uS2luZCI6InNvdXJjZS1nYXRlIiwibWV0cmljSWQiOiJVTlJFU09MVkVEX0lOVEVSVkFMX0NPTkZMSUNUX0NPVU5UIiwic291cmNlSWQiOiJTUkMtUDAtU1RVREVOVC0wMDEiLCJnYXRlSWQiOiJzdHVkZW50LWVmZmVjdGl2ZS1pbnRlcnZhbC1jb25mbGljdCIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU1JDLVAwLVNUVURFTlQtMDAxL3N0dWRlbnQtZWZmZWN0aXZlLWludGVydmFsLWNvbmZsaWN0IiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY29udGludWl0eSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJjb3VudCIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJ1bnJlc29sdmVkLWludGVydmFsLWNvbmZsaWN0cyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoiY29uc3RhbnQiLCJ2YWx1ZSI6MX19LCJ1bml0IjoiY291bnQiLCJvcGVyYXRvciI6Ij0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoiYWx3YXlzIn0sIm93bmVyIjoi5a2m57GN5Li75pWw5o2uIG93bmVyIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0KICAgICAgXSwKICAgICAgImZyZXNobmVzc0xhbmVzIjpbeyJsYW5lSWQiOiJpbmNyZW1lbnRhbC1yZWNvcmQiLCJ1bml0IjoicmVjb3JkIiwidGltZXpvbmUiOiJBc2lhL1NoYW5naGFpIiwiaW5jbHVzaXZlIjp0cnVlLCJhbGxvd05vQWN0aXZpdHkiOmZhbHNlLCJydWxlIjp7ImtpbmQiOiJkdXJhdGlvbiIsImxhdGVyRmllbGQiOiJtYW5pZmVzdC5yZWNlaXZlZEF0IiwiZWFybGllckZpZWxkIjoicmVjb3JkLnNvdXJjZVVwZGF0ZWRBdCIsIm1heER1cmF0aW9uTWlsbGlzZWNvbmRzIjoxNDQwMDAwMH19LHsibGFuZUlkIjoiZGFpbHktZnVsbC1wYXJ0aXRpb24iLCJ1bml0Ijoic2NoZWR1bGVkLXBhcnRpdGlvbiIsInRpbWV6b25lIjoiQXNpYS9TaGFuZ2hhaSIsImluY2x1c2l2ZSI6dHJ1ZSwiYWxsb3dOb0FjdGl2aXR5IjpmYWxzZSwicnVsZSI6eyJraW5kIjoibG9jYWwtY3V0b2ZmIiwibGF0ZXJGaWVsZCI6Im1hbmlmZXN0LnJlY2VpdmVkQXQiLCJlYXJsaWVyRmllbGQiOiJtYW5pZmVzdC5zY2hlZHVsZWREdWVBdCIsImR1ZUxvY2FsVGltZSI6IjA2OjAwOjAwIiwiZGF5T2Zmc2V0IjowfX1dCiAgICB9LAogICAgewogICAgICAic291cmNlSWQiOiJTUkMtUDAtUkVTUE9OU0lCSUxJVFktMDAxIiwib3duZXIiOiLnu4Tnu4fouqvku70gb3duZXIgKyDlrablt6XotKPku7vlhbPns7sgb3duZXIiLCJzY2hlbWFCaW5kaW5nIjp7InBhdGgiOiJjb250cmFjdHMvZGF0YS1jYXRhbG9nL3NvdXJjZXMvc3JjLXAwLXJlc3BvbnNpYmlsaXR5LTAwMS0yLjEuMC5zY2hlbWEuanNvbiIsInZlcnNpb24iOiJSRVNQT05TSUJJTElUWS1BVVRIT1JJVFktVjItMi4xLjAiLCJyYXdTaGEyNTYiOiJhNWUzYjdhMTY3M2IwYzU1ZDA5ZWFkZDRjYzQ5MWJlMjk3YWE0NmYyNGRjODNiYzZlNTYwNTNhMzNiOGI4NmExIiwiY2Fub25pY2FsRGlnZXN0Ijoic2hhMjU2OmIxNDU3MjE1ZWQ1ZWJmNmIyNjU2YWM5OTdiNjFmZDRhMmJjMTU2NzEwMWZjM2RjZmEzZWJmZjc4MmQ2Zjk2YTgifSwKICAgICAgImFwcGxpY2FibGVDb21tb25NZXRyaWNJZHMiOlsiUFJJTUFSWV9LRVlfQ09NUExFVEVORVNTX0JQIiwiUkVRVUlSRURfRklFTERfVkFMSURJVFlfQlAiLCJWQUxJRF9SRUNPUkRfUkFURV9CUCIsIkZSRVNITkVTU19XSVRISU5fU0xPX0JQIiwiVU5SRVNPTFZFRF9JTlRFUlZBTF9DT05GTElDVF9DT1VOVCIsIkRVUExJQ0FURV9CVVNJTkVTU19LRVlfQ09VTlQiLCJWRVJTSU9OX1JFR1JFU1NJT05fQ09VTlQiLCJTT1VSQ0VfQ09OVElOVUlUWV9HQVRFIiwiU0NIRU1BX0FMTE9XTElTVF9DT01QQVRJQklMSVRZX0JQIiwiRk9SQklEREVOX0ZJRUxEX0NPVU5UIl0sCiAgICAgICJzb3VyY2VHYXRlcyI6WwogICAgICAgIHsiZGVmaW5pdGlvbktpbmQiOiJzb3VyY2UtZ2F0ZSIsIm1ldHJpY0lkIjoiU09VUkNFX0NPTlRJTlVJVFlfR0FURSIsInNvdXJjZUlkIjoiU1JDLVAwLVJFU1BPTlNJQklMSVRZLTAwMSIsImdhdGVJZCI6ImFjdGl2ZS1hdXRob3JpdHktdW5tYXBwZWQiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL1NSQy1QMC1SRVNQT05TSUJJTElUWS0wMDEvYWN0aXZlLWF1dGhvcml0eS11bm1hcHBlZCIsImZvcm11bGFWZXJzaW9uIjoiMS4wLjAiLCJjYXRlZ29yeSI6ImNvbnRpbnVpdHkiLCJjYWxjdWxhdGlvbiI6eyJraW5kIjoiY291bnQiLCJudW1lcmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoidW5tYXBwZWQtYWN0aXZlLWF1dGhvcml0eS1ldmlkZW5jZSJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoiY29uc3RhbnQiLCJ2YWx1ZSI6MX19LCJ1bml0IjoiY291bnQiLCJvcGVyYXRvciI6Ij0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoiYWx3YXlzIn0sIm93bmVyIjoi57uE57uH6Lqr5Lu9IG93bmVyICsg5a2m5bel6LSj5Lu75YWz57O7IG93bmVyIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0sCiAgICAgICAgeyJkZWZpbml0aW9uS2luZCI6InNvdXJjZS1nYXRlIiwibWV0cmljSWQiOiJTT1VSQ0VfQ09OVElOVUlUWV9HQVRFIiwic291cmNlSWQiOiJTUkMtUDAtUkVTUE9OU0lCSUxJVFktMDAxIiwiZ2F0ZUlkIjoicmV2b2NhdGlvbi1kdXJhdGlvbiIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU1JDLVAwLVJFU1BPTlNJQklMSVRZLTAwMS9yZXZvY2F0aW9uLWR1cmF0aW9uIiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY29udGludWl0eSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJkdXJhdGlvbiIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJyZXZvY2F0aW9uLWR1cmF0aW9uLW1pbGxpc2Vjb25kcyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoiY29uc3RhbnQiLCJ2YWx1ZSI6MX19LCJ1bml0IjoibWlsbGlzZWNvbmQiLCJvcGVyYXRvciI6Ijw9IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjkwMDAwMCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoiYWx3YXlzIn0sIm93bmVyIjoi57uE57uH6Lqr5Lu9IG93bmVyICsg5a2m5bel6LSj5Lu75YWz57O7IG93bmVyIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0sCiAgICAgICAgeyJkZWZpbml0aW9uS2luZCI6InNvdXJjZS1nYXRlIiwibWV0cmljSWQiOiJWQUxJRF9SRUNPUkRfUkFURV9CUCIsInNvdXJjZUlkIjoiU1JDLVAwLVJFU1BPTlNJQklMSVRZLTAwMSIsImdhdGVJZCI6Im1hbmlmZXN0LXJlY29uY2lsZSIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU1JDLVAwLVJFU1BPTlNJQklMSVRZLTAwMS9tYW5pZmVzdC1yZWNvbmNpbGUiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb3ZlcmFnZSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJtYW5pZmVzdC1yZWNvbmNpbGVkLXJlY29yZHMifSwiZGVub21pbmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoibWFuaWZlc3QtZGVjbGFyZWQtcmVjb3JkLWNvdW50In19LCJ1bml0IjoiYmFzaXMtcG9pbnQiLCJvcGVyYXRvciI6Ij49IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjk5OTAsInRocmVzaG9sZERlbm9taW5hdG9yIjoxMDAwMCwiZGVub21pbmF0b3JaZXJvQmVoYXZpb3IiOiJldmFsdWF0aW9uLWVycm9yIiwidmFsdWVTY2FsZSI6MCwicm91bmRpbmdNb2RlIjoiSEFMRl9VUCIsImNvbXBhcmlzb25TdGFnZSI6InByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsImFwcGxpY2FiaWxpdHkiOnsicHJlZGljYXRlSWQiOiJhbHdheXMifSwib3duZXIiOiLnu4Tnu4fouqvku70gb3duZXIgKyDlrablt6XotKPku7vlhbPns7sgb3duZXIiLCJhcHByb3ZhbFJlZiI6IkFVVEgtMjAyNi0wOC0wOC0wMDEiLCJlZmZlY3RpdmVBdCI6IjIwMjYtMDgtMDlUMTA6MDI6MjIrMDg6MDAiLCJldmlkZW5jZVJlZiI6InN0b3J5Oi8vMi4zL0RFQy0wMTkvQVVUSC0yMDI2LTA4LTA4LTAwMSIsImhhcmRHYXRlIjp0cnVlfQogICAgICBdLAogICAgICAiZnJlc2huZXNzTGFuZXMiOlt7ImxhbmVJZCI6ImluY3JlbWVudGFsLWF1dGhvcml0eS1mYWN0IiwidW5pdCI6ImF1dGhvcml0eS1mYWN0IiwidGltZXpvbmUiOiJBc2lhL1NoYW5naGFpIiwiaW5jbHVzaXZlIjp0cnVlLCJhbGxvd05vQWN0aXZpdHkiOmZhbHNlLCJydWxlIjp7ImtpbmQiOiJkdXJhdGlvbiIsImxhdGVyRmllbGQiOiJtYW5pZmVzdC5yZWNlaXZlZEF0IiwiZWFybGllckZpZWxkIjoibWFuaWZlc3Quc291cmNlT2NjdXJyZWRBdCIsIm1heER1cmF0aW9uTWlsbGlzZWNvbmRzIjo5MDAwMDB9fSx7ImxhbmVJZCI6ImRhaWx5LWZ1bGwtcGFydGl0aW9uIiwidW5pdCI6InNjaGVkdWxlZC1wYXJ0aXRpb24iLCJ0aW1lem9uZSI6IkFzaWEvU2hhbmdoYWkiLCJpbmNsdXNpdmUiOnRydWUsImFsbG93Tm9BY3Rpdml0eSI6ZmFsc2UsInJ1bGUiOnsia2luZCI6ImxvY2FsLWN1dG9mZiIsImxhdGVyRmllbGQiOiJtYW5pZmVzdC5yZWNlaXZlZEF0IiwiZWFybGllckZpZWxkIjoibWFuaWZlc3Quc2NoZWR1bGVkRHVlQXQiLCJkdWVMb2NhbFRpbWUiOiIwNjowMDowMCIsImRheU9mZnNldCI6MH19XQogICAgfSwKICAgIHsKICAgICAgInNvdXJjZUlkIjoiU1JDLVAwLUFDQ09NTU9EQVRJT04tMDAxIiwib3duZXIiOiLlrr/nrqHmlbDmja4gb3duZXIgKyDlrablt6XkvY/lrr/lpIfmoYggb3duZXIiLCJzY2hlbWFCaW5kaW5nIjp7InBhdGgiOiJjb250cmFjdHMvZGF0YS1jYXRhbG9nL3NvdXJjZXMvc3JjLXAwLWFjY29tbW9kYXRpb24tMDAxLnNjaGVtYS5qc29uIiwidmVyc2lvbiI6IkFDQ09NTU9EQVRJT04tU0xJQ0UtMS4wLjAiLCJyYXdTaGEyNTYiOiIwMmY5ZTQxZTI1OWNmMDYwYjIzOTU5NjkyMWVhZDNlYmVmYzAxODVlMmYyYmU0NzA4NmM0MTE1NTExNDdjNjZiIiwiY2Fub25pY2FsRGlnZXN0Ijoic2hhMjU2OmViMDZmNGVlYTg0NTMxZTA0NjE5MTFiZWYyNzI0MTliMjJkNjk3MzdkY2IwZDczYjEyYjI4ZDJlN2ZkZTI5MjcifSwKICAgICAgImFwcGxpY2FibGVDb21tb25NZXRyaWNJZHMiOlsiUFJJTUFSWV9LRVlfQ09NUExFVEVORVNTX0JQIiwiUDBfU1VCSkVDVF9NQVBQSU5HX0JQIiwiUkVRVUlSRURfRklFTERfVkFMSURJVFlfQlAiLCJWQUxJRF9SRUNPUkRfUkFURV9CUCIsIkNPUkVfRklFTERfQ09WRVJBR0VfQlAiLCJGUkVTSE5FU1NfV0lUSElOX1NMT19CUCIsIlVOUkVTT0xWRURfSU5URVJWQUxfQ09ORkxJQ1RfQ09VTlQiLCJEVVBMSUNBVEVfQlVTSU5FU1NfS0VZX0NPVU5UIiwiVkVSU0lPTl9SRUdSRVNTSU9OX0NPVU5UIiwiU09VUkNFX0NPTlRJTlVJVFlfR0FURSIsIlNDSEVNQV9BTExPV0xJU1RfQ09NUEFUSUJJTElUWV9CUCIsIkZPUkJJRERFTl9GSUVMRF9DT1VOVCJdLAogICAgICAic291cmNlR2F0ZXMiOlsKICAgICAgICB7ImRlZmluaXRpb25LaW5kIjoic291cmNlLWdhdGUiLCJtZXRyaWNJZCI6IlVOUkVTT0xWRURfSU5URVJWQUxfQ09ORkxJQ1RfQ09VTlQiLCJzb3VyY2VJZCI6IlNSQy1QMC1BQ0NPTU1PREFUSU9OLTAwMSIsImdhdGVJZCI6ImNvbmN1cnJlbnQtaW50ZXJ2YWwtY29uZmxpY3QiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL1NSQy1QMC1BQ0NPTU1PREFUSU9OLTAwMS9jb25jdXJyZW50LWludGVydmFsLWNvbmZsaWN0IiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY29udGludWl0eSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJjb3VudCIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJjb25jdXJyZW50LWludGVydmFsLWNvbmZsaWN0cyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoiY29uc3RhbnQiLCJ2YWx1ZSI6MX19LCJ1bml0IjoiY291bnQiLCJvcGVyYXRvciI6Ij0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoiYWx3YXlzIn0sIm93bmVyIjoi5a6/566h5pWw5o2uIG93bmVyICsg5a2m5bel5L2P5a6/5aSH5qGIIG93bmVyIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0sCiAgICAgICAgeyJkZWZpbml0aW9uS2luZCI6InNvdXJjZS1nYXRlIiwibWV0cmljSWQiOiJDT1JFX0ZJRUxEX0NPVkVSQUdFX0JQIiwic291cmNlSWQiOiJTUkMtUDAtQUNDT01NT0RBVElPTi0wMDEiLCJnYXRlSWQiOiJhY2NvbW1vZGF0aW9uLWNvcmUtZmllbGQtZ3JvdXAiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL1NSQy1QMC1BQ0NPTU1PREFUSU9OLTAwMS9hY2NvbW1vZGF0aW9uLWNvcmUtZmllbGQtZ3JvdXAiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb3ZlcmFnZSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJyZWNvcmRzLXBhc3Npbmctc291cmNlLWZpZWxkLWdyb3VwIn0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6InJlY29yZHMtYXBwbGljYWJsZS10by1zb3VyY2UtZmllbGQtZ3JvdXAifX0sInVuaXQiOiJiYXNpcy1wb2ludCIsIm9wZXJhdG9yIjoiPj0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6OTk1MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEwMDAwLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6InNvdXJjZS1maWVsZC1ncm91cC1wcmVzZW50In0sImZpZWxkU2V0IjpbInN0dWRlbnRSZWYiLCJhY2NvbW1vZGF0aW9uVHlwZSIsImNhbXB1c0NvZGUiLCJlZmZlY3RpdmVGcm9tIiwiZWZmZWN0aXZlVG8iXSwib3duZXIiOiLlrr/nrqHmlbDmja4gb3duZXIgKyDlrablt6XkvY/lrr/lpIfmoYggb3duZXIiLCJhcHByb3ZhbFJlZiI6IkFVVEgtMjAyNi0wOC0wOC0wMDEiLCJlZmZlY3RpdmVBdCI6IjIwMjYtMDgtMDlUMTA6MDI6MjIrMDg6MDAiLCJldmlkZW5jZVJlZiI6InN0b3J5Oi8vMi4zL0RFQy0wMTkvQVVUSC0yMDI2LTA4LTA4LTAwMSIsImhhcmRHYXRlIjp0cnVlfQogICAgICBdLAogICAgICAiZnJlc2huZXNzTGFuZXMiOlt7ImxhbmVJZCI6ImNoYW5nZS1yZWNvcmQiLCJ1bml0IjoicmVjb3JkIiwidGltZXpvbmUiOiJBc2lhL1NoYW5naGFpIiwiaW5jbHVzaXZlIjp0cnVlLCJhbGxvd05vQWN0aXZpdHkiOmZhbHNlLCJydWxlIjp7ImtpbmQiOiJkdXJhdGlvbiIsImxhdGVyRmllbGQiOiJtYW5pZmVzdC5yZWNlaXZlZEF0IiwiZWFybGllckZpZWxkIjoicmVjb3JkLnNvdXJjZVVwZGF0ZWRBdCIsIm1heER1cmF0aW9uTWlsbGlzZWNvbmRzIjozNjAwMDAwfX0seyJsYW5lSWQiOiJkYWlseS1mdWxsLXBhcnRpdGlvbiIsInVuaXQiOiJzY2hlZHVsZWQtcGFydGl0aW9uIiwidGltZXpvbmUiOiJBc2lhL1NoYW5naGFpIiwiaW5jbHVzaXZlIjp0cnVlLCJhbGxvd05vQWN0aXZpdHkiOmZhbHNlLCJydWxlIjp7ImtpbmQiOiJsb2NhbC1jdXRvZmYiLCJsYXRlckZpZWxkIjoibWFuaWZlc3QucmVjZWl2ZWRBdCIsImVhcmxpZXJGaWVsZCI6Im1hbmlmZXN0LnNjaGVkdWxlZER1ZUF0IiwiZHVlTG9jYWxUaW1lIjoiMDY6MDA6MDAiLCJkYXlPZmZzZXQiOjB9fV0KICAgIH0sCiAgICB7CiAgICAgICJzb3VyY2VJZCI6IlNSQy1QMC1DQVJELTAwMSIsIm93bmVyIjoi5LiA5Y2h6YCa5pWw5o2uIG93bmVyIiwic2NoZW1hQmluZGluZyI6eyJwYXRoIjoiY29udHJhY3RzL2RhdGEtY2F0YWxvZy9zb3VyY2VzL3NyYy1wMC1jYXJkLTAwMS5zY2hlbWEuanNvbiIsInZlcnNpb24iOiJDQVJELVNMSUNFLTEuMC4wIiwicmF3U2hhMjU2IjoiYjljM2NmNTc2MjliYzdmZmNiOTIyYmI0MDlkYjY1ODE1ZjRmZDU3NzhjNTM3NGM3ZGJhZjQzYTBmYTRkMDg2MyIsImNhbm9uaWNhbERpZ2VzdCI6InNoYTI1Njo5NTZkN2NmZmE2ZWM2MmYyMzU5ZmVkNGU4YmJmNzhmM2QxMTljMTc4YjljNGM3MDY4MTRmNGZjZGI3NmIwODRlIn0sCiAgICAgICJhcHBsaWNhYmxlQ29tbW9uTWV0cmljSWRzIjpbIlBSSU1BUllfS0VZX0NPTVBMRVRFTkVTU19CUCIsIlAwX1NVQkpFQ1RfTUFQUElOR19CUCIsIlJFUVVJUkVEX0ZJRUxEX1ZBTElESVRZX0JQIiwiVkFMSURfUkVDT1JEX1JBVEVfQlAiLCJDT1JFX0ZJRUxEX0NPVkVSQUdFX0JQIiwiRlJFU0hORVNTX1dJVEhJTl9TTE9fQlAiLCJVTlJFU09MVkVEX0lOVEVSVkFMX0NPTkZMSUNUX0NPVU5UIiwiRFVQTElDQVRFX0JVU0lORVNTX0tFWV9DT1VOVCIsIlZFUlNJT05fUkVHUkVTU0lPTl9DT1VOVCIsIlNPVVJDRV9DT05USU5VSVRZX0dBVEUiLCJTQ0hFTUFfQUxMT1dMSVNUX0NPTVBBVElCSUxJVFlfQlAiLCJGT1JCSURERU5fRklFTERfQ09VTlQiXSwKICAgICAgInNvdXJjZUdhdGVzIjpbCiAgICAgICAgeyJkZWZpbml0aW9uS2luZCI6InNvdXJjZS1nYXRlIiwibWV0cmljSWQiOiJDT1JFX0ZJRUxEX0NPVkVSQUdFX0JQIiwic291cmNlSWQiOiJTUkMtUDAtQ0FSRC0wMDEiLCJnYXRlSWQiOiJjYXJkLWNvcmUtZmllbGQtZ3JvdXAiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL1NSQy1QMC1DQVJELTAwMS9jYXJkLWNvcmUtZmllbGQtZ3JvdXAiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb3ZlcmFnZSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJyZWNvcmRzLXBhc3Npbmctc291cmNlLWZpZWxkLWdyb3VwIn0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6InJlY29yZHMtYXBwbGljYWJsZS10by1zb3VyY2UtZmllbGQtZ3JvdXAifX0sInVuaXQiOiJiYXNpcy1wb2ludCIsIm9wZXJhdG9yIjoiPj0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6OTk1MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEwMDAwLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6InNvdXJjZS1maWVsZC1ncm91cC1wcmVzZW50In0sImZpZWxkU2V0IjpbImV2ZW50SWQiLCJzdWJqZWN0UmVmIiwiY2F0ZWdvcnlDb2RlIiwiYW1vdW50TWlub3IiLCJjdXJyZW5jeSJdLCJvd25lciI6IuS4gOWNoemAmuaVsOaNriBvd25lciIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9LAogICAgICAgIHsiZGVmaW5pdGlvbktpbmQiOiJzb3VyY2UtZ2F0ZSIsIm1ldHJpY0lkIjoiU09VUkNFX0NPTlRJTlVJVFlfR0FURSIsInNvdXJjZUlkIjoiU1JDLVAwLUNBUkQtMDAxIiwiZ2F0ZUlkIjoiY29ycmVjdGlvbi1jaGFpbi1jb21wbGV0ZSIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU1JDLVAwLUNBUkQtMDAxL2NvcnJlY3Rpb24tY2hhaW4tY29tcGxldGUiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb250aW51aXR5IiwiY2FsY3VsYXRpb24iOnsia2luZCI6ImNvdW50IiwibnVtZXJhdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6ImNvcnJlY3Rpb24tY2hhaW4tdmlvbGF0aW9ucyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoiY29uc3RhbnQiLCJ2YWx1ZSI6MX19LCJ1bml0IjoiY291bnQiLCJvcGVyYXRvciI6Ij0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoiYWx3YXlzIn0sIm93bmVyIjoi5LiA5Y2h6YCa5pWw5o2uIG93bmVyIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0KICAgICAgXSwKICAgICAgImZyZXNobmVzc0xhbmVzIjpbeyJsYW5lSWQiOiJ0cmFuc2FjdGlvbi1yZWNvcmQiLCJ1bml0IjoicmVjb3JkIiwidGltZXpvbmUiOiJBc2lhL1NoYW5naGFpIiwiaW5jbHVzaXZlIjp0cnVlLCJhbGxvd05vQWN0aXZpdHkiOmZhbHNlLCJydWxlIjp7ImtpbmQiOiJkdXJhdGlvbiIsImxhdGVyRmllbGQiOiJyZWNvcmQucmVjZWl2ZWRBdCIsImVhcmxpZXJGaWVsZCI6InJlY29yZC5vY2N1cnJlZEF0IiwibWF4RHVyYXRpb25NaWxsaXNlY29uZHMiOjkwMDAwMH19LHsibGFuZUlkIjoic2V0dGxlbWVudC1wYXJ0aXRpb24iLCJ1bml0Ijoic2NoZWR1bGVkLXBhcnRpdGlvbiIsInRpbWV6b25lIjoiQXNpYS9TaGFuZ2hhaSIsImluY2x1c2l2ZSI6dHJ1ZSwiYWxsb3dOb0FjdGl2aXR5IjpmYWxzZSwicnVsZSI6eyJraW5kIjoibG9jYWwtY3V0b2ZmIiwibGF0ZXJGaWVsZCI6Im1hbmlmZXN0LnJlY2VpdmVkQXQiLCJlYXJsaWVyRmllbGQiOiJtYW5pZmVzdC5zY2hlZHVsZWREdWVBdCIsImR1ZUxvY2FsVGltZSI6IjA2OjAwOjAwIiwiZGF5T2Zmc2V0IjoxfX1dCiAgICB9LAogICAgewogICAgICAic291cmNlSWQiOiJTUkMtUDAtQ0FNUFVTLUFDQ0VTUy0wMDEiLCJvd25lciI6IuS/neWNq+agoemXqOaVsOaNriBvd25lciIsInNjaGVtYUJpbmRpbmciOnsicGF0aCI6ImNvbnRyYWN0cy9kYXRhLWNhdGFsb2cvc291cmNlcy9zcmMtcDAtY2FtcHVzLWFjY2Vzcy0wMDEuc2NoZW1hLmpzb24iLCJ2ZXJzaW9uIjoiQ0FNUFVTLUFDQ0VTUy1TTElDRS0xLjAuMCIsInJhd1NoYTI1NiI6ImNmMTNmODQ2ZjMwOTQzOWI0MjkzMjU2NjNlZWY3N2E5MWU3MzZkMmEzNTE0MGQ3ZTA5MzE1OWRjYWI4MzVhZTkiLCJjYW5vbmljYWxEaWdlc3QiOiJzaGEyNTY6YWQ0NTk0YzljZmU4YTU5ZjZmZGY2NWQ1NjA5Yjg1ZTExNTVkYmU3YzhmZmIyMDg4ZWEyNzc0ZWQwODMwYTVlOCJ9LAogICAgICAiYXBwbGljYWJsZUNvbW1vbk1ldHJpY0lkcyI6WyJQUklNQVJZX0tFWV9DT01QTEVURU5FU1NfQlAiLCJQMF9TVUJKRUNUX01BUFBJTkdfQlAiLCJSRVFVSVJFRF9GSUVMRF9WQUxJRElUWV9CUCIsIlZBTElEX1JFQ09SRF9SQVRFX0JQIiwiQ09SRV9GSUVMRF9DT1ZFUkFHRV9CUCIsIkZSRVNITkVTU19XSVRISU5fU0xPX0JQIiwiVU5SRVNPTFZFRF9JTlRFUlZBTF9DT05GTElDVF9DT1VOVCIsIkRVUExJQ0FURV9CVVNJTkVTU19LRVlfQ09VTlQiLCJWRVJTSU9OX1JFR1JFU1NJT05fQ09VTlQiLCJTT1VSQ0VfQ09OVElOVUlUWV9HQVRFIiwiU0NIRU1BX0FMTE9XTElTVF9DT01QQVRJQklMSVRZX0JQIiwiRk9SQklEREVOX0ZJRUxEX0NPVU5UIl0sCiAgICAgICJzb3VyY2VHYXRlcyI6WwogICAgICAgIHsiZGVmaW5pdGlvbktpbmQiOiJzb3VyY2UtZ2F0ZSIsIm1ldHJpY0lkIjoiRFVQTElDQVRFX0JVU0lORVNTX0tFWV9DT1VOVCIsInNvdXJjZUlkIjoiU1JDLVAwLUNBTVBVUy1BQ0NFU1MtMDAxIiwiZ2F0ZUlkIjoiZXZlbnQtaWQtZHVwbGljYXRlIiwiZm9ybXVsYUlkIjoiUU1EUC0xLjAuMC9TUkMtUDAtQ0FNUFVTLUFDQ0VTUy0wMDEvZXZlbnQtaWQtZHVwbGljYXRlIiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY29udGludWl0eSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJjb3VudCIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJkdXBsaWNhdGUtZXZlbnQtaWRzIn0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJjb25zdGFudCIsInZhbHVlIjoxfX0sInVuaXQiOiJjb3VudCIsIm9wZXJhdG9yIjoiPSIsImJvdW5kYXJ5IjoiaW5jbHVzaXZlIiwidGhyZXNob2xkTnVtZXJhdG9yIjowLCJ0aHJlc2hvbGREZW5vbWluYXRvciI6MSwiZGVub21pbmF0b3JaZXJvQmVoYXZpb3IiOiJldmFsdWF0aW9uLWVycm9yIiwidmFsdWVTY2FsZSI6MCwicm91bmRpbmdNb2RlIjoiSEFMRl9VUCIsImNvbXBhcmlzb25TdGFnZSI6InByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsImFwcGxpY2FiaWxpdHkiOnsicHJlZGljYXRlSWQiOiJhbHdheXMifSwib3duZXIiOiLkv53ljavmoKHpl6jmlbDmja4gb3duZXIiLCJhcHByb3ZhbFJlZiI6IkFVVEgtMjAyNi0wOC0wOC0wMDEiLCJlZmZlY3RpdmVBdCI6IjIwMjYtMDgtMDlUMTA6MDI6MjIrMDg6MDAiLCJldmlkZW5jZVJlZiI6InN0b3J5Oi8vMi4zL0RFQy0wMTkvQVVUSC0yMDI2LTA4LTA4LTAwMSIsImhhcmRHYXRlIjp0cnVlfSwKICAgICAgICB7ImRlZmluaXRpb25LaW5kIjoic291cmNlLWdhdGUiLCJtZXRyaWNJZCI6IkNPUkVfRklFTERfQ09WRVJBR0VfQlAiLCJzb3VyY2VJZCI6IlNSQy1QMC1DQU1QVVMtQUNDRVNTLTAwMSIsImdhdGVJZCI6ImRldmljZS1kaXJlY3Rpb24tdGltZS1maWVsZC1ncm91cCIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU1JDLVAwLUNBTVBVUy1BQ0NFU1MtMDAxL2RldmljZS1kaXJlY3Rpb24tdGltZS1maWVsZC1ncm91cCIsImZvcm11bGFWZXJzaW9uIjoiMS4wLjAiLCJjYXRlZ29yeSI6ImNvdmVyYWdlIiwiY2FsY3VsYXRpb24iOnsia2luZCI6InJhdGlvIiwibnVtZXJhdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6InJlY29yZHMtcGFzc2luZy1zb3VyY2UtZmllbGQtZ3JvdXAifSwiZGVub21pbmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoicmVjb3Jkcy1hcHBsaWNhYmxlLXRvLXNvdXJjZS1maWVsZC1ncm91cCJ9fSwidW5pdCI6ImJhc2lzLXBvaW50Iiwib3BlcmF0b3IiOiI+PSIsImJvdW5kYXJ5IjoiaW5jbHVzaXZlIiwidGhyZXNob2xkTnVtZXJhdG9yIjo5OTkwLCJ0aHJlc2hvbGREZW5vbWluYXRvciI6MTAwMDAsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoic291cmNlLWZpZWxkLWdyb3VwLXByZXNlbnQifSwiZmllbGRTZXQiOlsiZGV2aWNlSWQiLCJkaXJlY3Rpb24iLCJvY2N1cnJlZEF0Il0sIm93bmVyIjoi5L+d5Y2r5qCh6Zeo5pWw5o2uIG93bmVyIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0KICAgICAgXSwKICAgICAgImZyZXNobmVzc0xhbmVzIjpbeyJsYW5lSWQiOiJldmVudC1yZWNvcmQiLCJ1bml0IjoicmVjb3JkIiwidGltZXpvbmUiOiJBc2lhL1NoYW5naGFpIiwiaW5jbHVzaXZlIjp0cnVlLCJhbGxvd05vQWN0aXZpdHkiOmZhbHNlLCJydWxlIjp7ImtpbmQiOiJkdXJhdGlvbiIsImxhdGVyRmllbGQiOiJyZWNvcmQucmVjZWl2ZWRBdCIsImVhcmxpZXJGaWVsZCI6InJlY29yZC5vY2N1cnJlZEF0IiwibWF4RHVyYXRpb25NaWxsaXNlY29uZHMiOjMwMDAwMH19LHsibGFuZUlkIjoiZGFpbHktcmVjb25jaWxlLXBhcnRpdGlvbiIsInVuaXQiOiJzY2hlZHVsZWQtcGFydGl0aW9uIiwidGltZXpvbmUiOiJBc2lhL1NoYW5naGFpIiwiaW5jbHVzaXZlIjp0cnVlLCJhbGxvd05vQWN0aXZpdHkiOmZhbHNlLCJydWxlIjp7ImtpbmQiOiJsb2NhbC1jdXRvZmYiLCJsYXRlckZpZWxkIjoibWFuaWZlc3QucmVjZWl2ZWRBdCIsImVhcmxpZXJGaWVsZCI6Im1hbmlmZXN0LnNjaGVkdWxlZER1ZUF0IiwiZHVlTG9jYWxUaW1lIjoiMDY6MDA6MDAiLCJkYXlPZmZzZXQiOjB9fV0KICAgIH0sCiAgICB7CiAgICAgICJzb3VyY2VJZCI6IlNSQy1QMC1ET1JNLUFDQ0VTUy0wMDEiLCJvd25lciI6IuWuv+iIjemXqOemgeaVsOaNriBvd25lciIsInNjaGVtYUJpbmRpbmciOnsicGF0aCI6ImNvbnRyYWN0cy9kYXRhLWNhdGFsb2cvc291cmNlcy9zcmMtcDAtZG9ybS1hY2Nlc3MtMDAxLnNjaGVtYS5qc29uIiwidmVyc2lvbiI6IkRPUk0tQUNDRVNTLVNMSUNFLTEuMC4wIiwicmF3U2hhMjU2IjoiOTI0ZTY1ZjM1Nzk0ZmJjMTdmNmE5NWJjNzgwODZhNTFiOThhZGFjYTFjYTZiYTYyZDA5NjdlMGU1MGE3Njc2MSIsImNhbm9uaWNhbERpZ2VzdCI6InNoYTI1NjpmMWIzN2E4ODhhMWUxZmI2NzU3YzJiZGQyMjQ5OWYyYjQwYTQwMGFlMDBmNjQxYWRlOGNlYjAzMTIyMzQ5ZjE0In0sCiAgICAgICJhcHBsaWNhYmxlQ29tbW9uTWV0cmljSWRzIjpbIlBSSU1BUllfS0VZX0NPTVBMRVRFTkVTU19CUCIsIlAwX1NVQkpFQ1RfTUFQUElOR19CUCIsIlJFUVVJUkVEX0ZJRUxEX1ZBTElESVRZX0JQIiwiVkFMSURfUkVDT1JEX1JBVEVfQlAiLCJDT1JFX0ZJRUxEX0NPVkVSQUdFX0JQIiwiRlJFU0hORVNTX1dJVEhJTl9TTE9fQlAiLCJVTlJFU09MVkVEX0lOVEVSVkFMX0NPTkZMSUNUX0NPVU5UIiwiRFVQTElDQVRFX0JVU0lORVNTX0tFWV9DT1VOVCIsIlZFUlNJT05fUkVHUkVTU0lPTl9DT1VOVCIsIlNPVVJDRV9DT05USU5VSVRZX0dBVEUiLCJTQ0hFTUFfQUxMT1dMSVNUX0NPTVBBVElCSUxJVFlfQlAiLCJGT1JCSURERU5fRklFTERfQ09VTlQiXSwKICAgICAgInNvdXJjZUdhdGVzIjpbCiAgICAgICAgeyJkZWZpbml0aW9uS2luZCI6InNvdXJjZS1nYXRlIiwibWV0cmljSWQiOiJEVVBMSUNBVEVfQlVTSU5FU1NfS0VZX0NPVU5UIiwic291cmNlSWQiOiJTUkMtUDAtRE9STS1BQ0NFU1MtMDAxIiwiZ2F0ZUlkIjoiZXZlbnQtaWQtZHVwbGljYXRlIiwiZm9ybXVsYUlkIjoiUU1EUC0xLjAuMC9TUkMtUDAtRE9STS1BQ0NFU1MtMDAxL2V2ZW50LWlkLWR1cGxpY2F0ZSIsImZvcm11bGFWZXJzaW9uIjoiMS4wLjAiLCJjYXRlZ29yeSI6ImNvbnRpbnVpdHkiLCJjYWxjdWxhdGlvbiI6eyJraW5kIjoiY291bnQiLCJudW1lcmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoiZHVwbGljYXRlLWV2ZW50LWlkcyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoiY29uc3RhbnQiLCJ2YWx1ZSI6MX19LCJ1bml0IjoiY291bnQiLCJvcGVyYXRvciI6Ij0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoiYWx3YXlzIn0sIm93bmVyIjoi5a6/6IiN6Zeo56aB5pWw5o2uIG93bmVyIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0sCiAgICAgICAgeyJkZWZpbml0aW9uS2luZCI6InNvdXJjZS1nYXRlIiwibWV0cmljSWQiOiJDT1JFX0ZJRUxEX0NPVkVSQUdFX0JQIiwic291cmNlSWQiOiJTUkMtUDAtRE9STS1BQ0NFU1MtMDAxIiwiZ2F0ZUlkIjoiYnVpbGRpbmctY2F0YWxvZy1tYXAiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL1NSQy1QMC1ET1JNLUFDQ0VTUy0wMDEvYnVpbGRpbmctY2F0YWxvZy1tYXAiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb3ZlcmFnZSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJidWlsZGluZy1jYXRhbG9nLW1hcHBlZC1yZWNvcmRzIn0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6InJlY29yZHMtYXBwbGljYWJsZS10by1zb3VyY2UtZmllbGQtZ3JvdXAifX0sInVuaXQiOiJiYXNpcy1wb2ludCIsIm9wZXJhdG9yIjoiPj0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6OTk5MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEwMDAwLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6InNvdXJjZS1maWVsZC1ncm91cC1wcmVzZW50In0sImZpZWxkU2V0IjpbImJ1aWxkaW5nQ29kZSJdLCJvd25lciI6IuWuv+iIjemXqOemgeaVsOaNriBvd25lciIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9CiAgICAgIF0sCiAgICAgICJmcmVzaG5lc3NMYW5lcyI6W3sibGFuZUlkIjoiZXZlbnQtcmVjb3JkIiwidW5pdCI6InJlY29yZCIsInRpbWV6b25lIjoiQXNpYS9TaGFuZ2hhaSIsImluY2x1c2l2ZSI6dHJ1ZSwiYWxsb3dOb0FjdGl2aXR5IjpmYWxzZSwicnVsZSI6eyJraW5kIjoiZHVyYXRpb24iLCJsYXRlckZpZWxkIjoicmVjb3JkLnJlY2VpdmVkQXQiLCJlYXJsaWVyRmllbGQiOiJyZWNvcmQub2NjdXJyZWRBdCIsIm1heER1cmF0aW9uTWlsbGlzZWNvbmRzIjozMDAwMDB9fSx7ImxhbmVJZCI6ImRhaWx5LXJlY29uY2lsZS1wYXJ0aXRpb24iLCJ1bml0Ijoic2NoZWR1bGVkLXBhcnRpdGlvbiIsInRpbWV6b25lIjoiQXNpYS9TaGFuZ2hhaSIsImluY2x1c2l2ZSI6dHJ1ZSwiYWxsb3dOb0FjdGl2aXR5IjpmYWxzZSwicnVsZSI6eyJraW5kIjoibG9jYWwtY3V0b2ZmIiwibGF0ZXJGaWVsZCI6Im1hbmlmZXN0LnJlY2VpdmVkQXQiLCJlYXJsaWVyRmllbGQiOiJtYW5pZmVzdC5zY2hlZHVsZWREdWVBdCIsImR1ZUxvY2FsVGltZSI6IjA2OjAwOjAwIiwiZGF5T2Zmc2V0IjowfX1dCiAgICB9LAogICAgewogICAgICAic291cmNlSWQiOiJTUkMtUDAtREVWSUNFLTAwMSIsIm93bmVyIjoi6Zeo56aB6K6+5aSHIG93bmVyIiwic2NoZW1hQmluZGluZyI6eyJwYXRoIjoiY29udHJhY3RzL2RhdGEtY2F0YWxvZy9zb3VyY2VzL3NyYy1wMC1kZXZpY2UtMDAxLnNjaGVtYS5qc29uIiwidmVyc2lvbiI6IkRFVklDRS1TTElDRS0xLjAuMCIsInJhd1NoYTI1NiI6ImRmYTI4NTI4YzUxMzMyMzdjOTYxNGU3MzU4Nzk3YmZkOGZiMmIyOTkxMzcwMjY2ZmQyMzE4NmE1YTlmYjU0YjUiLCJjYW5vbmljYWxEaWdlc3QiOiJzaGEyNTY6NTA5OTVkMTRiNmM2MGM4OTQ2NThkOWFlMjJkNDg2YTlhZWZiODllMTA0YWNiODQ0MGRjZDJkMGZlM2RiOWVjZCJ9LAogICAgICAiYXBwbGljYWJsZUNvbW1vbk1ldHJpY0lkcyI6WyJQUklNQVJZX0tFWV9DT01QTEVURU5FU1NfQlAiLCJSRVFVSVJFRF9GSUVMRF9WQUxJRElUWV9CUCIsIlZBTElEX1JFQ09SRF9SQVRFX0JQIiwiQ09SRV9GSUVMRF9DT1ZFUkFHRV9CUCIsIkZSRVNITkVTU19XSVRISU5fU0xPX0JQIiwiVU5SRVNPTFZFRF9JTlRFUlZBTF9DT05GTElDVF9DT1VOVCIsIkRVUExJQ0FURV9CVVNJTkVTU19LRVlfQ09VTlQiLCJWRVJTSU9OX1JFR1JFU1NJT05fQ09VTlQiLCJTT1VSQ0VfQ09OVElOVUlUWV9HQVRFIiwiU0NIRU1BX0FMTE9XTElTVF9DT01QQVRJQklMSVRZX0JQIiwiRk9SQklEREVOX0ZJRUxEX0NPVU5UIl0sCiAgICAgICJzb3VyY2VHYXRlcyI6WwogICAgICAgIHsiZGVmaW5pdGlvbktpbmQiOiJzb3VyY2UtZ2F0ZSIsIm1ldHJpY0lkIjoiQ09SRV9GSUVMRF9DT1ZFUkFHRV9CUCIsInNvdXJjZUlkIjoiU1JDLVAwLURFVklDRS0wMDEiLCJnYXRlSWQiOiJkZXZpY2UtY2F0YWxvZy1tYXAiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL1NSQy1QMC1ERVZJQ0UtMDAxL2RldmljZS1jYXRhbG9nLW1hcCIsImZvcm11bGFWZXJzaW9uIjoiMS4wLjAiLCJjYXRlZ29yeSI6ImNvdmVyYWdlIiwiY2FsY3VsYXRpb24iOnsia2luZCI6InJhdGlvIiwibnVtZXJhdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6ImRldmljZS1tYXAtdmFsaWQtcmVjb3JkcyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJyZWNvcmRzLWFwcGxpY2FibGUtdG8tc291cmNlLWZpZWxkLWdyb3VwIn19LCJ1bml0IjoiYmFzaXMtcG9pbnQiLCJvcGVyYXRvciI6Ij0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6MSwidGhyZXNob2xkRGVub21pbmF0b3IiOjEsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoic291cmNlLWZpZWxkLWdyb3VwLXByZXNlbnQifSwiZmllbGRTZXQiOlsiZGV2aWNlSWQiLCJsb2NhdGlvbkNvZGUiLCJhcHBsaWVzVG9Tb3VyY2VJZCJdLCJvd25lciI6IumXqOemgeiuvuWkhyBvd25lciIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9LAogICAgICAgIHsiZGVmaW5pdGlvbktpbmQiOiJzb3VyY2UtZ2F0ZSIsIm1ldHJpY0lkIjoiU09VUkNFX0NPTlRJTlVJVFlfR0FURSIsInNvdXJjZUlkIjoiU1JDLVAwLURFVklDRS0wMDEiLCJnYXRlSWQiOiJoZWFydGJlYXQtZ2FwLWV4cGxhaW5lZCIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU1JDLVAwLURFVklDRS0wMDEvaGVhcnRiZWF0LWdhcC1leHBsYWluZWQiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb250aW51aXR5IiwiY2FsY3VsYXRpb24iOnsia2luZCI6ImNvdW50IiwibnVtZXJhdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6InVuZXhwbGFpbmVkLWhlYXJ0YmVhdC1nYXBzIn0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJjb25zdGFudCIsInZhbHVlIjoxfX0sInVuaXQiOiJjb3VudCIsIm9wZXJhdG9yIjoiPSIsImJvdW5kYXJ5IjoiaW5jbHVzaXZlIiwidGhyZXNob2xkTnVtZXJhdG9yIjowLCJ0aHJlc2hvbGREZW5vbWluYXRvciI6MSwiZGVub21pbmF0b3JaZXJvQmVoYXZpb3IiOiJldmFsdWF0aW9uLWVycm9yIiwidmFsdWVTY2FsZSI6MCwicm91bmRpbmdNb2RlIjoiSEFMRl9VUCIsImNvbXBhcmlzb25TdGFnZSI6InByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsImFwcGxpY2FiaWxpdHkiOnsicHJlZGljYXRlSWQiOiJhbHdheXMifSwib3duZXIiOiLpl6jnpoHorr7lpIcgb3duZXIiLCJhcHByb3ZhbFJlZiI6IkFVVEgtMjAyNi0wOC0wOC0wMDEiLCJlZmZlY3RpdmVBdCI6IjIwMjYtMDgtMDlUMTA6MDI6MjIrMDg6MDAiLCJldmlkZW5jZVJlZiI6InN0b3J5Oi8vMi4zL0RFQy0wMTkvQVVUSC0yMDI2LTA4LTA4LTAwMSIsImhhcmRHYXRlIjp0cnVlfQogICAgICBdLAogICAgICAiZnJlc2huZXNzTGFuZXMiOlt7ImxhbmVJZCI6ImhlYXJ0YmVhdC1yZWNvcmQiLCJ1bml0IjoicmVjb3JkIiwidGltZXpvbmUiOiJBc2lhL1NoYW5naGFpIiwiaW5jbHVzaXZlIjp0cnVlLCJhbGxvd05vQWN0aXZpdHkiOmZhbHNlLCJydWxlIjp7ImtpbmQiOiJkdXJhdGlvbiIsImxhdGVyRmllbGQiOiJtYW5pZmVzdC5yZWNlaXZlZEF0IiwiZWFybGllckZpZWxkIjoicmVjb3JkLmhlYXJ0YmVhdEF0IiwibWF4RHVyYXRpb25NaWxsaXNlY29uZHMiOjMwMDAwMH19LHsibGFuZUlkIjoiZmF1bHQtZmFjdCIsInVuaXQiOiJmYXVsdC1mYWN0IiwidGltZXpvbmUiOiJBc2lhL1NoYW5naGFpIiwiaW5jbHVzaXZlIjp0cnVlLCJhbGxvd05vQWN0aXZpdHkiOmZhbHNlLCJydWxlIjp7ImtpbmQiOiJkdXJhdGlvbiIsImxhdGVyRmllbGQiOiJtYW5pZmVzdC5yZWNlaXZlZEF0IiwiZWFybGllckZpZWxkIjoicmVjb3JkLmVmZmVjdGl2ZUZyb20iLCJtYXhEdXJhdGlvbk1pbGxpc2Vjb25kcyI6NjAwMDAwfX1dCiAgICB9LAogICAgewogICAgICAic291cmNlSWQiOiJTUkMtUDAtTEVBVkUtMDAxIiwib3duZXIiOiLlrablt6Xor7flgYcv5a6e5Lmg5pWw5o2uIG93bmVyIiwic2NoZW1hQmluZGluZyI6eyJwYXRoIjoiY29udHJhY3RzL2RhdGEtY2F0YWxvZy9zb3VyY2VzL3NyYy1wMC1sZWF2ZS0wMDEuc2NoZW1hLmpzb24iLCJ2ZXJzaW9uIjoiTEVBVkUtU0xJQ0UtMS4wLjAiLCJyYXdTaGEyNTYiOiJkMmQzNzFiMjVhZTE3Mjk0YzNmNjJhZDM1NGE0Y2E1YzdkOTU5NDhjMDc4Y2NlNTYyNDFhNWJkYWU1YWQxZjFhIiwiY2Fub25pY2FsRGlnZXN0Ijoic2hhMjU2OjIxMDdiNjNlZGMxNjdlYmU4MzhiMzgwYmIzOTQyMGJlNGY1MWJkMGYwZjJlNDUzNGY5MTQ2YTE2M2ZjNzkyNjMifSwKICAgICAgImFwcGxpY2FibGVDb21tb25NZXRyaWNJZHMiOlsiUFJJTUFSWV9LRVlfQ09NUExFVEVORVNTX0JQIiwiUDBfU1VCSkVDVF9NQVBQSU5HX0JQIiwiUkVRVUlSRURfRklFTERfVkFMSURJVFlfQlAiLCJWQUxJRF9SRUNPUkRfUkFURV9CUCIsIkNPUkVfRklFTERfQ09WRVJBR0VfQlAiLCJGUkVTSE5FU1NfV0lUSElOX1NMT19CUCIsIlVOUkVTT0xWRURfSU5URVJWQUxfQ09ORkxJQ1RfQ09VTlQiLCJEVVBMSUNBVEVfQlVTSU5FU1NfS0VZX0NPVU5UIiwiVkVSU0lPTl9SRUdSRVNTSU9OX0NPVU5UIiwiU09VUkNFX0NPTlRJTlVJVFlfR0FURSIsIlNDSEVNQV9BTExPV0xJU1RfQ09NUEFUSUJJTElUWV9CUCIsIkZPUkJJRERFTl9GSUVMRF9DT1VOVCJdLAogICAgICAic291cmNlR2F0ZXMiOlsKICAgICAgICB7ImRlZmluaXRpb25LaW5kIjoic291cmNlLWdhdGUiLCJtZXRyaWNJZCI6IkNPUkVfRklFTERfQ09WRVJBR0VfQlAiLCJzb3VyY2VJZCI6IlNSQy1QMC1MRUFWRS0wMDEiLCJnYXRlSWQiOiJsZWF2ZS1jb3JlLWZpZWxkLWdyb3VwIiwiZm9ybXVsYUlkIjoiUU1EUC0xLjAuMC9TUkMtUDAtTEVBVkUtMDAxL2xlYXZlLWNvcmUtZmllbGQtZ3JvdXAiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb3ZlcmFnZSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJyZWNvcmRzLXBhc3Npbmctc291cmNlLWZpZWxkLWdyb3VwIn0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6InJlY29yZHMtYXBwbGljYWJsZS10by1zb3VyY2UtZmllbGQtZ3JvdXAifX0sInVuaXQiOiJiYXNpcy1wb2ludCIsIm9wZXJhdG9yIjoiPj0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6OTk5MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEwMDAwLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6InNvdXJjZS1maWVsZC1ncm91cC1wcmVzZW50In0sImZpZWxkU2V0IjpbImZpbGluZ1R5cGUiLCJlZmZlY3RpdmVGcm9tIiwiZWZmZWN0aXZlVG8iLCJhcHByb3ZhbFN0YXRlIiwic291cmNlVmVyc2lvbiJdLCJvd25lciI6IuWtpuW3peivt+WBhy/lrp7kuaDmlbDmja4gb3duZXIiLCJhcHByb3ZhbFJlZiI6IkFVVEgtMjAyNi0wOC0wOC0wMDEiLCJlZmZlY3RpdmVBdCI6IjIwMjYtMDgtMDlUMTA6MDI6MjIrMDg6MDAiLCJldmlkZW5jZVJlZiI6InN0b3J5Oi8vMi4zL0RFQy0wMTkvQVVUSC0yMDI2LTA4LTA4LTAwMSIsImhhcmRHYXRlIjp0cnVlfSwKICAgICAgICB7ImRlZmluaXRpb25LaW5kIjoic291cmNlLWdhdGUiLCJtZXRyaWNJZCI6IlVOUkVTT0xWRURfSU5URVJWQUxfQ09ORkxJQ1RfQ09VTlQiLCJzb3VyY2VJZCI6IlNSQy1QMC1MRUFWRS0wMDEiLCJnYXRlSWQiOiJmaWxpbmctaW50ZXJ2YWwtY29uZmxpY3QiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL1NSQy1QMC1MRUFWRS0wMDEvZmlsaW5nLWludGVydmFsLWNvbmZsaWN0IiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY29udGludWl0eSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJjb3VudCIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJ1bnJlc29sdmVkLWludGVydmFsLWNvbmZsaWN0cyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoiY29uc3RhbnQiLCJ2YWx1ZSI6MX19LCJ1bml0IjoiY291bnQiLCJvcGVyYXRvciI6Ij0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoiYWx3YXlzIn0sIm93bmVyIjoi5a2m5bel6K+35YGHL+WunuS5oOaVsOaNriBvd25lciIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9CiAgICAgIF0sCiAgICAgICJmcmVzaG5lc3NMYW5lcyI6W3sibGFuZUlkIjoiYXBwcm92YWwtcmV2b2NhdGlvbi1yZWNvcmQiLCJ1bml0IjoicmVjb3JkIiwidGltZXpvbmUiOiJBc2lhL1NoYW5naGFpIiwiaW5jbHVzaXZlIjp0cnVlLCJhbGxvd05vQWN0aXZpdHkiOmZhbHNlLCJydWxlIjp7ImtpbmQiOiJkdXJhdGlvbiIsImxhdGVyRmllbGQiOiJtYW5pZmVzdC5yZWNlaXZlZEF0IiwiZWFybGllckZpZWxkIjoicmVjb3JkLnNvdXJjZVVwZGF0ZWRBdCIsIm1heER1cmF0aW9uTWlsbGlzZWNvbmRzIjo5MDAwMDB9fSx7ImxhbmVJZCI6ImRhaWx5LXJlY29uY2lsZS1wYXJ0aXRpb24iLCJ1bml0Ijoic2NoZWR1bGVkLXBhcnRpdGlvbiIsInRpbWV6b25lIjoiQXNpYS9TaGFuZ2hhaSIsImluY2x1c2l2ZSI6dHJ1ZSwiYWxsb3dOb0FjdGl2aXR5IjpmYWxzZSwicnVsZSI6eyJraW5kIjoibG9jYWwtY3V0b2ZmIiwibGF0ZXJGaWVsZCI6Im1hbmlmZXN0LnJlY2VpdmVkQXQiLCJlYXJsaWVyRmllbGQiOiJtYW5pZmVzdC5zY2hlZHVsZWREdWVBdCIsImR1ZUxvY2FsVGltZSI6IjA2OjAwOjAwIiwiZGF5T2Zmc2V0IjowfX1dCiAgICB9LAogICAgewogICAgICAic291cmNlSWQiOiJTUkMtUDAtQ0FMRU5EQVItMDAxIiwib3duZXIiOiLmoKHljobmlbDmja4gb3duZXIiLCJzY2hlbWFCaW5kaW5nIjp7InBhdGgiOiJjb250cmFjdHMvZGF0YS1jYXRhbG9nL3NvdXJjZXMvc3JjLXAwLWNhbGVuZGFyLTAwMS5zY2hlbWEuanNvbiIsInZlcnNpb24iOiJCQy0xLjAuMCIsInJhd1NoYTI1NiI6IjY0NGIxZjllMTgyMjI4MmMyYmIxNjA5NjA2NzRmZjI5NzU1NmZkZTg5MWIwMDUwZjg2YmQ0NDBkNzE0YjJhMzUiLCJjYW5vbmljYWxEaWdlc3QiOiJzaGEyNTY6NzQzNmM5NzA5ZTFjMzMxYzk5ZDZjMDE4ZDMxMjk1MzdiYjRjMjRkYjE0NjIzOTZiM2I4MmFiY2JlMmNmMmY4NyJ9LAogICAgICAiYXBwbGljYWJsZUNvbW1vbk1ldHJpY0lkcyI6WyJQUklNQVJZX0tFWV9DT01QTEVURU5FU1NfQlAiLCJSRVFVSVJFRF9GSUVMRF9WQUxJRElUWV9CUCIsIlZBTElEX1JFQ09SRF9SQVRFX0JQIiwiRlJFU0hORVNTX1dJVEhJTl9TTE9fQlAiLCJVTlJFU09MVkVEX0lOVEVSVkFMX0NPTkZMSUNUX0NPVU5UIiwiRFVQTElDQVRFX0JVU0lORVNTX0tFWV9DT1VOVCIsIlZFUlNJT05fUkVHUkVTU0lPTl9DT1VOVCIsIlNPVVJDRV9DT05USU5VSVRZX0dBVEUiLCJTQ0hFTUFfQUxMT1dMSVNUX0NPTVBBVElCSUxJVFlfQlAiLCJGT1JCSURERU5fRklFTERfQ09VTlQiXSwKICAgICAgInNvdXJjZUdhdGVzIjpbCiAgICAgICAgeyJkZWZpbml0aW9uS2luZCI6InNvdXJjZS1nYXRlIiwibWV0cmljSWQiOiJTT1VSQ0VfQ09OVElOVUlUWV9HQVRFIiwic291cmNlSWQiOiJTUkMtUDAtQ0FMRU5EQVItMDAxIiwiZ2F0ZUlkIjoiY2FsZW5kYXItZXhhY3RseS1vbmUtY3VycmVudC1kYXktdHlwZSIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU1JDLVAwLUNBTEVOREFSLTAwMS9jYWxlbmRhci1leGFjdGx5LW9uZS1jdXJyZW50LWRheS10eXBlIiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY29udGludWl0eSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJjYWxlbmRhci1jdXJyZW50LWRheXMifSwiZGVub21pbmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoiY2FsZW5kYXItZXhwZWN0ZWQtZGF5cyJ9fSwidW5pdCI6ImJhc2lzLXBvaW50Iiwib3BlcmF0b3IiOiI9IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjEsInRocmVzaG9sZERlbm9taW5hdG9yIjoxLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6ImFsd2F5cyJ9LCJvd25lciI6IuagoeWOhuaVsOaNriBvd25lciIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9CiAgICAgIF0sCiAgICAgICJmcmVzaG5lc3NMYW5lcyI6W3sibGFuZUlkIjoibm9ybWFsLXByb2plY3Rpb24iLCJ1bml0IjoiY2FsZW5kYXItcHJvamVjdGlvbiIsInRpbWV6b25lIjoiQXNpYS9TaGFuZ2hhaSIsImluY2x1c2l2ZSI6dHJ1ZSwiYWxsb3dOb0FjdGl2aXR5IjpmYWxzZSwicnVsZSI6eyJraW5kIjoiYWR2YW5jZS1ob3Jpem9uIiwibGF0ZXJGaWVsZCI6Im1hbmlmZXN0LnNjaGVkdWxlZER1ZUF0IiwiZWFybGllckZpZWxkIjoibWFuaWZlc3QucmVjZWl2ZWRBdCIsIm1pbmltdW1MZWFkTWlsbGlzZWNvbmRzIjo2MDQ4MDAwMDB9fSx7ImxhbmVJZCI6ImVtZXJnZW5jeS1jb3JyZWN0aW9uIiwidW5pdCI6InJlY29yZCIsInRpbWV6b25lIjoiQXNpYS9TaGFuZ2hhaSIsImluY2x1c2l2ZSI6dHJ1ZSwiYWxsb3dOb0FjdGl2aXR5IjpmYWxzZSwicnVsZSI6eyJraW5kIjoiZHVyYXRpb24iLCJsYXRlckZpZWxkIjoibWFuaWZlc3QucmVjZWl2ZWRBdCIsImVhcmxpZXJGaWVsZCI6InJlY29yZC5lZmZlY3RpdmVBdCIsIm1heER1cmF0aW9uTWlsbGlzZWNvbmRzIjozNjAwMDAwfX1dCiAgICB9LAogICAgewogICAgICAic291cmNlSWQiOiJTUkMtUDAtVElNRVRBQkxFLTAwMSIsIm93bmVyIjoi5pWZ5Yqh6K++6KGo5pWw5o2uIG93bmVyIiwic2NoZW1hQmluZGluZyI6eyJwYXRoIjoiY29udHJhY3RzL2RhdGEtY2F0YWxvZy9zb3VyY2VzL3NyYy1wMC10aW1ldGFibGUtMDAxLnNjaGVtYS5qc29uIiwidmVyc2lvbiI6IlRJTUVUQUJMRS1TTElDRS0xLjAuMCIsInJhd1NoYTI1NiI6IjczMWY3OTRiNzcxMjFmNDgxY2Q2ZWMzNmE3MjEzYzU3ZGMyOGMwNTNiMzg5YmRlODM5MjRhZDJhN2ZlNDlmZDIiLCJjYW5vbmljYWxEaWdlc3QiOiJzaGEyNTY6NGI0OTdiOWY2ZmIyY2IwZmNiODE2ODk3ZjJlYzUyOTk0ODAyNmVhYmJhNmY4YzU1ZjdkOWRhOGM4YTllNTZlMiJ9LAogICAgICAiYXBwbGljYWJsZUNvbW1vbk1ldHJpY0lkcyI6WyJQUklNQVJZX0tFWV9DT01QTEVURU5FU1NfQlAiLCJQMF9TVUJKRUNUX01BUFBJTkdfQlAiLCJSRVFVSVJFRF9GSUVMRF9WQUxJRElUWV9CUCIsIlZBTElEX1JFQ09SRF9SQVRFX0JQIiwiQ09SRV9GSUVMRF9DT1ZFUkFHRV9CUCIsIkZSRVNITkVTU19XSVRISU5fU0xPX0JQIiwiVU5SRVNPTFZFRF9JTlRFUlZBTF9DT05GTElDVF9DT1VOVCIsIkRVUExJQ0FURV9CVVNJTkVTU19LRVlfQ09VTlQiLCJWRVJTSU9OX1JFR1JFU1NJT05fQ09VTlQiLCJTT1VSQ0VfQ09OVElOVUlUWV9HQVRFIiwiU0NIRU1BX0FMTE9XTElTVF9DT01QQVRJQklMSVRZX0JQIiwiRk9SQklEREVOX0ZJRUxEX0NPVU5UIl0sCiAgICAgICJzb3VyY2VHYXRlcyI6WwogICAgICAgIHsiZGVmaW5pdGlvbktpbmQiOiJzb3VyY2UtZ2F0ZSIsIm1ldHJpY0lkIjoiQ09SRV9GSUVMRF9DT1ZFUkFHRV9CUCIsInNvdXJjZUlkIjoiU1JDLVAwLVRJTUVUQUJMRS0wMDEiLCJnYXRlSWQiOiJ0aW1ldGFibGUtY29yZS1maWVsZC1ncm91cCIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU1JDLVAwLVRJTUVUQUJMRS0wMDEvdGltZXRhYmxlLWNvcmUtZmllbGQtZ3JvdXAiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb3ZlcmFnZSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJyZWNvcmRzLXBhc3Npbmctc291cmNlLWZpZWxkLWdyb3VwIn0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6InJlY29yZHMtYXBwbGljYWJsZS10by1zb3VyY2UtZmllbGQtZ3JvdXAifX0sInVuaXQiOiJiYXNpcy1wb2ludCIsIm9wZXJhdG9yIjoiPj0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6OTk1MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEwMDAwLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6InNvdXJjZS1maWVsZC1ncm91cC1wcmVzZW50In0sImZpZWxkU2V0IjpbImFjdGl2aXR5U3RhdGUiLCJjYW1wdXNDb2RlIiwibG9jYXRpb25Db2RlIiwiZW5yb2xsbWVudFN0YXRlIiwiZWZmZWN0aXZlQXQiXSwib3duZXIiOiLmlZnliqHor77ooajmlbDmja4gb3duZXIiLCJhcHByb3ZhbFJlZiI6IkFVVEgtMjAyNi0wOC0wOC0wMDEiLCJlZmZlY3RpdmVBdCI6IjIwMjYtMDgtMDlUMTA6MDI6MjIrMDg6MDAiLCJldmlkZW5jZVJlZiI6InN0b3J5Oi8vMi4zL0RFQy0wMTkvQVVUSC0yMDI2LTA4LTA4LTAwMSIsImhhcmRHYXRlIjp0cnVlfSwKICAgICAgICB7ImRlZmluaXRpb25LaW5kIjoic291cmNlLWdhdGUiLCJtZXRyaWNJZCI6IlZFUlNJT05fUkVHUkVTU0lPTl9DT1VOVCIsInNvdXJjZUlkIjoiU1JDLVAwLVRJTUVUQUJMRS0wMDEiLCJnYXRlSWQiOiJidXNpbmVzcy1rZXktdmVyc2lvbi1yZWdyZXNzaW9uIiwiZm9ybXVsYUlkIjoiUU1EUC0xLjAuMC9TUkMtUDAtVElNRVRBQkxFLTAwMS9idXNpbmVzcy1rZXktdmVyc2lvbi1yZWdyZXNzaW9uIiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY29udGludWl0eSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJjb3VudCIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJ2ZXJzaW9uLXJlZ3Jlc3Npb25zIn0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJjb25zdGFudCIsInZhbHVlIjoxfX0sInVuaXQiOiJjb3VudCIsIm9wZXJhdG9yIjoiPSIsImJvdW5kYXJ5IjoiaW5jbHVzaXZlIiwidGhyZXNob2xkTnVtZXJhdG9yIjowLCJ0aHJlc2hvbGREZW5vbWluYXRvciI6MSwiZGVub21pbmF0b3JaZXJvQmVoYXZpb3IiOiJldmFsdWF0aW9uLWVycm9yIiwidmFsdWVTY2FsZSI6MCwicm91bmRpbmdNb2RlIjoiSEFMRl9VUCIsImNvbXBhcmlzb25TdGFnZSI6InByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsImFwcGxpY2FiaWxpdHkiOnsicHJlZGljYXRlSWQiOiJhbHdheXMifSwib3duZXIiOiLmlZnliqHor77ooajmlbDmja4gb3duZXIiLCJhcHByb3ZhbFJlZiI6IkFVVEgtMjAyNi0wOC0wOC0wMDEiLCJlZmZlY3RpdmVBdCI6IjIwMjYtMDgtMDlUMTA6MDI6MjIrMDg6MDAiLCJldmlkZW5jZVJlZiI6InN0b3J5Oi8vMi4zL0RFQy0wMTkvQVVUSC0yMDI2LTA4LTA4LTAwMSIsImhhcmRHYXRlIjp0cnVlfQogICAgICBdLAogICAgICAiZnJlc2huZXNzTGFuZXMiOlt7ImxhbmVJZCI6ImNhbmNlbGxhdGlvbi1yZXNjaGVkdWxlLXJlY29yZCIsInVuaXQiOiJyZWNvcmQiLCJ0aW1lem9uZSI6IkFzaWEvU2hhbmdoYWkiLCJpbmNsdXNpdmUiOnRydWUsImFsbG93Tm9BY3Rpdml0eSI6ZmFsc2UsInJ1bGUiOnsia2luZCI6ImR1cmF0aW9uIiwibGF0ZXJGaWVsZCI6Im1hbmlmZXN0LnJlY2VpdmVkQXQiLCJlYXJsaWVyRmllbGQiOiJyZWNvcmQuc291cmNlVXBkYXRlZEF0IiwibWF4RHVyYXRpb25NaWxsaXNlY29uZHMiOjE4MDAwMDB9fSx7ImxhbmVJZCI6ImRhaWx5LWZ1bGwtcGFydGl0aW9uIiwidW5pdCI6InNjaGVkdWxlZC1wYXJ0aXRpb24iLCJ0aW1lem9uZSI6IkFzaWEvU2hhbmdoYWkiLCJpbmNsdXNpdmUiOnRydWUsImFsbG93Tm9BY3Rpdml0eSI6ZmFsc2UsInJ1bGUiOnsia2luZCI6ImxvY2FsLWN1dG9mZiIsImxhdGVyRmllbGQiOiJtYW5pZmVzdC5yZWNlaXZlZEF0IiwiZWFybGllckZpZWxkIjoibWFuaWZlc3Quc2NoZWR1bGVkRHVlQXQiLCJkdWVMb2NhbFRpbWUiOiIwNjowMDowMCIsImRheU9mZnNldCI6MH19XQogICAgfSwKICAgIHsKICAgICAgInNvdXJjZUlkIjoiU1JDLVAxLU9GRkNBTVBVUy0wMDEiLCJvd25lciI6IuWtpuW3peemu+agoeWkh+ahiCBvd25lciIsInNjaGVtYUJpbmRpbmciOnsicGF0aCI6ImNvbnRyYWN0cy9kYXRhLWNhdGFsb2cvc291cmNlcy9zcmMtcDEtb2ZmY2FtcHVzLTAwMS5zY2hlbWEuanNvbiIsInZlcnNpb24iOiJPRkZDQU1QVVMtU0xJQ0UtMS4wLjAiLCJyYXdTaGEyNTYiOiI2NjJmNzc5MDM5MWVjOWM4NmRkMTMyYTNmZDVlN2NhNzVlN2Q1NmQwMmE4MjgzY2JhOGY1MGQ4YzNjMTMwYzVjIiwiY2Fub25pY2FsRGlnZXN0Ijoic2hhMjU2OjNmOTMyMDMyZmJjODhjYTliYzA1N2NkZGE1NDU2ZjI1OGQ5NjVkZDJkZDM3YzNkMDE0OTgyMzZlMWYwOTQ0OTYifSwKICAgICAgImFwcGxpY2FibGVDb21tb25NZXRyaWNJZHMiOlsiUFJJTUFSWV9LRVlfQ09NUExFVEVORVNTX0JQIiwiUkVRVUlSRURfRklFTERfVkFMSURJVFlfQlAiLCJWQUxJRF9SRUNPUkRfUkFURV9CUCIsIkNPUkVfRklFTERfQ09WRVJBR0VfQlAiLCJGUkVTSE5FU1NfV0lUSElOX1NMT19CUCIsIlVOUkVTT0xWRURfSU5URVJWQUxfQ09ORkxJQ1RfQ09VTlQiLCJEVVBMSUNBVEVfQlVTSU5FU1NfS0VZX0NPVU5UIiwiVkVSU0lPTl9SRUdSRVNTSU9OX0NPVU5UIiwiU09VUkNFX0NPTlRJTlVJVFlfR0FURSIsIlNDSEVNQV9BTExPV0xJU1RfQ09NUEFUSUJJTElUWV9CUCIsIkZPUkJJRERFTl9GSUVMRF9DT1VOVCJdLAogICAgICAic291cmNlR2F0ZXMiOlsKICAgICAgICB7ImRlZmluaXRpb25LaW5kIjoic291cmNlLWdhdGUiLCJtZXRyaWNJZCI6IkNPUkVfRklFTERfQ09WRVJBR0VfQlAiLCJzb3VyY2VJZCI6IlNSQy1QMS1PRkZDQU1QVVMtMDAxIiwiZ2F0ZUlkIjoicDAtYWNjb21tb2RhdGlvbi1vdmVybGFwLWRpc2FtYmlndWF0aW9uIiwiZm9ybXVsYUlkIjoiUU1EUC0xLjAuMC9TUkMtUDEtT0ZGQ0FNUFVTLTAwMS9wMC1hY2NvbW1vZGF0aW9uLW92ZXJsYXAtZGlzYW1iaWd1YXRpb24iLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb3ZlcmFnZSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJvdmVybGFwLWRpc2FtYmlndWF0ZWQtcmVjb3JkcyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJvdmVybGFwLXJlY29yZHMifX0sInVuaXQiOiJiYXNpcy1wb2ludCIsIm9wZXJhdG9yIjoiPSIsImJvdW5kYXJ5IjoiaW5jbHVzaXZlIiwidGhyZXNob2xkTnVtZXJhdG9yIjoxLCJ0aHJlc2hvbGREZW5vbWluYXRvciI6MSwiZGVub21pbmF0b3JaZXJvQmVoYXZpb3IiOiJldmFsdWF0aW9uLWVycm9yIiwidmFsdWVTY2FsZSI6MCwicm91bmRpbmdNb2RlIjoiSEFMRl9VUCIsImNvbXBhcmlzb25TdGFnZSI6InByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsImFwcGxpY2FiaWxpdHkiOnsicHJlZGljYXRlSWQiOiJvdmVybGFwLXJlY29yZHMtcHJlc2VudCJ9LCJmaWVsZFNldCI6WyJwMEFjY29tbW9kYXRpb25EaXNhbWJpZ3VhdGlvbiJdLCJvd25lciI6IuWtpuW3peemu+agoeWkh+ahiCBvd25lciIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9CiAgICAgIF0sCiAgICAgICJmcmVzaG5lc3NMYW5lcyI6W3sibGFuZUlkIjoiY2hhbmdlLXJlY29yZCIsInVuaXQiOiJyZWNvcmQiLCJ0aW1lem9uZSI6IkFzaWEvU2hhbmdoYWkiLCJpbmNsdXNpdmUiOnRydWUsImFsbG93Tm9BY3Rpdml0eSI6ZmFsc2UsInJ1bGUiOnsia2luZCI6ImR1cmF0aW9uIiwibGF0ZXJGaWVsZCI6Im1hbmlmZXN0LnJlY2VpdmVkQXQiLCJlYXJsaWVyRmllbGQiOiJyZWNvcmQuZWZmZWN0aXZlRnJvbSIsIm1heER1cmF0aW9uTWlsbGlzZWNvbmRzIjozNjAwMDAwfX0seyJsYW5lSWQiOiJkYWlseS1yZWNvbmNpbGUtcGFydGl0aW9uIiwidW5pdCI6InNjaGVkdWxlZC1wYXJ0aXRpb24iLCJ0aW1lem9uZSI6IkFzaWEvU2hhbmdoYWkiLCJpbmNsdXNpdmUiOnRydWUsImFsbG93Tm9BY3Rpdml0eSI6ZmFsc2UsInJ1bGUiOnsia2luZCI6ImxvY2FsLWN1dG9mZiIsImxhdGVyRmllbGQiOiJtYW5pZmVzdC5yZWNlaXZlZEF0IiwiZWFybGllckZpZWxkIjoibWFuaWZlc3Quc2NoZWR1bGVkRHVlQXQiLCJkdWVMb2NhbFRpbWUiOiIwNjowMDowMCIsImRheU9mZnNldCI6MH19XQogICAgfSwKICAgIHsKICAgICAgInNvdXJjZUlkIjoiU1JDLVAxLU5FVFdPUkstMDAxIiwib3duZXIiOiLnvZHnu5zmlbDmja4gb3duZXIiLCJzY2hlbWFCaW5kaW5nIjp7InBhdGgiOiJjb250cmFjdHMvZGF0YS1jYXRhbG9nL3NvdXJjZXMvc3JjLXAxLW5ldHdvcmstMDAxLnNjaGVtYS5qc29uIiwidmVyc2lvbiI6Ik5FVFdPUkstQUdHUkVHQVRFLTEuMC4wIiwicmF3U2hhMjU2IjoiOWFmY2YyNTk2MWM1NDUyMjU2OTkxODgyOTNjYzlmMzI1ZThkNzllMDZhZmVjNTU2NjI2YzExNGIwMGY4ZjBmMiIsImNhbm9uaWNhbERpZ2VzdCI6InNoYTI1NjpkNTJjMGJmNGE2YTNmYzY1M2Y3MzcxNTQ4NDA4NjkzNjdhMDRiNzAxZmQ5MWVkMDA2NTJhYTkwNDk5NmI0NGVhIn0sCiAgICAgICJhcHBsaWNhYmxlQ29tbW9uTWV0cmljSWRzIjpbIlBSSU1BUllfS0VZX0NPTVBMRVRFTkVTU19CUCIsIlJFUVVJUkVEX0ZJRUxEX1ZBTElESVRZX0JQIiwiVkFMSURfUkVDT1JEX1JBVEVfQlAiLCJGUkVTSE5FU1NfV0lUSElOX1NMT19CUCIsIlVOUkVTT0xWRURfSU5URVJWQUxfQ09ORkxJQ1RfQ09VTlQiLCJEVVBMSUNBVEVfQlVTSU5FU1NfS0VZX0NPVU5UIiwiVkVSU0lPTl9SRUdSRVNTSU9OX0NPVU5UIiwiU09VUkNFX0NPTlRJTlVJVFlfR0FURSIsIlNDSEVNQV9BTExPV0xJU1RfQ09NUEFUSUJJTElUWV9CUCIsIkZPUkJJRERFTl9GSUVMRF9DT1VOVCJdLAogICAgICAic291cmNlR2F0ZXMiOlsKICAgICAgICB7ImRlZmluaXRpb25LaW5kIjoic291cmNlLWdhdGUiLCJtZXRyaWNJZCI6IkZPUkJJRERFTl9GSUVMRF9DT1VOVCIsInNvdXJjZUlkIjoiU1JDLVAxLU5FVFdPUkstMDAxIiwiZ2F0ZUlkIjoiZm9yYmlkZGVuLWNvbnRlbnQtZmllbGQtY291bnQiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL1NSQy1QMS1ORVRXT1JLLTAwMS9mb3JiaWRkZW4tY29udGVudC1maWVsZC1jb3VudCIsImZvcm11bGFWZXJzaW9uIjoiMS4wLjAiLCJjYXRlZ29yeSI6InByaXZhY3kiLCJjYWxjdWxhdGlvbiI6eyJraW5kIjoiY291bnQiLCJudW1lcmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoiZm9yYmlkZGVuLWNvbnRlbnQtaGl0cyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoiY29uc3RhbnQiLCJ2YWx1ZSI6MX19LCJ1bml0IjoiY291bnQiLCJvcGVyYXRvciI6Ij0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoiYWx3YXlzIn0sIm93bmVyIjoi572R57uc5pWw5o2uIG93bmVyIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0sCiAgICAgICAgeyJkZWZpbml0aW9uS2luZCI6InNvdXJjZS1nYXRlIiwibWV0cmljSWQiOiJWQUxJRF9SRUNPUkRfUkFURV9CUCIsInNvdXJjZUlkIjoiU1JDLVAxLU5FVFdPUkstMDAxIiwiZ2F0ZUlkIjoibWFuaWZlc3QtcGFydGl0aW9uLXJlY29uY2lsZSIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU1JDLVAxLU5FVFdPUkstMDAxL21hbmlmZXN0LXBhcnRpdGlvbi1yZWNvbmNpbGUiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb3ZlcmFnZSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJtYW5pZmVzdC1yZWNvbmNpbGVkLXBhcnRpdGlvbnMifSwiZGVub21pbmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoiZXhwZWN0ZWQtZGVsaXZlcnktdW5pdHMifX0sInVuaXQiOiJiYXNpcy1wb2ludCIsIm9wZXJhdG9yIjoiPSIsImJvdW5kYXJ5IjoiaW5jbHVzaXZlIiwidGhyZXNob2xkTnVtZXJhdG9yIjoxLCJ0aHJlc2hvbGREZW5vbWluYXRvciI6MSwiZGVub21pbmF0b3JaZXJvQmVoYXZpb3IiOiJldmFsdWF0aW9uLWVycm9yIiwidmFsdWVTY2FsZSI6MCwicm91bmRpbmdNb2RlIjoiSEFMRl9VUCIsImNvbXBhcmlzb25TdGFnZSI6InByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsImFwcGxpY2FiaWxpdHkiOnsicHJlZGljYXRlSWQiOiJhbHdheXMifSwib3duZXIiOiLnvZHnu5zmlbDmja4gb3duZXIiLCJhcHByb3ZhbFJlZiI6IkFVVEgtMjAyNi0wOC0wOC0wMDEiLCJlZmZlY3RpdmVBdCI6IjIwMjYtMDgtMDlUMTA6MDI6MjIrMDg6MDAiLCJldmlkZW5jZVJlZiI6InN0b3J5Oi8vMi4zL0RFQy0wMTkvQVVUSC0yMDI2LTA4LTA4LTAwMSIsImhhcmRHYXRlIjp0cnVlfQogICAgICBdLAogICAgICAiZnJlc2huZXNzTGFuZXMiOlt7ImxhbmVJZCI6ImNvbXBsZXRlLXBhcnRpdGlvbiIsInVuaXQiOiJzY2hlZHVsZWQtcGFydGl0aW9uIiwidGltZXpvbmUiOiJBc2lhL1NoYW5naGFpIiwiaW5jbHVzaXZlIjp0cnVlLCJhbGxvd05vQWN0aXZpdHkiOmZhbHNlLCJydWxlIjp7ImtpbmQiOiJsb2NhbC1jdXRvZmYiLCJsYXRlckZpZWxkIjoibWFuaWZlc3QucmVjZWl2ZWRBdCIsImVhcmxpZXJGaWVsZCI6Im1hbmlmZXN0LnNjaGVkdWxlZER1ZUF0IiwiZHVlTG9jYWxUaW1lIjoiMDg6MDA6MDAiLCJkYXlPZmZzZXQiOjF9fV0KICAgIH0sCiAgICB7CiAgICAgICJzb3VyY2VJZCI6IlNSQy1QMS1BQ0FERU1JQy0wMDEiLCJvd25lciI6IuaVmeWKoeWtpuS4muaVsOaNriBvd25lciIsInNjaGVtYUJpbmRpbmciOnsicGF0aCI6ImNvbnRyYWN0cy9kYXRhLWNhdGFsb2cvc291cmNlcy9zcmMtcDEtYWNhZGVtaWMtMDAxLnNjaGVtYS5qc29uIiwidmVyc2lvbiI6IkFDQURFTUlDLU5PREUtMS4wLjAiLCJyYXdTaGEyNTYiOiI5MzJkNTFiNWM5YmFmNzU4MmNkNjUwODdmNzIwYjI3MWJlY2NmYTMwOTFhMDUyZjQyYjY1ODgzYzYzNjBlNTc2IiwiY2Fub25pY2FsRGlnZXN0Ijoic2hhMjU2OmY3YzA3MmQ2NGZmNDE1ZWFjNTU1NGE5ZjRmMGVjNzJlOGZlM2NjZTRhNjMzOGZmZjYwODQ3NjVkNDA1MmZjZGUifSwKICAgICAgImFwcGxpY2FibGVDb21tb25NZXRyaWNJZHMiOlsiUFJJTUFSWV9LRVlfQ09NUExFVEVORVNTX0JQIiwiUkVRVUlSRURfRklFTERfVkFMSURJVFlfQlAiLCJWQUxJRF9SRUNPUkRfUkFURV9CUCIsIkZSRVNITkVTU19XSVRISU5fU0xPX0JQIiwiVU5SRVNPTFZFRF9JTlRFUlZBTF9DT05GTElDVF9DT1VOVCIsIkRVUExJQ0FURV9CVVNJTkVTU19LRVlfQ09VTlQiLCJWRVJTSU9OX1JFR1JFU1NJT05fQ09VTlQiLCJTT1VSQ0VfQ09OVElOVUlUWV9HQVRFIiwiU0NIRU1BX0FMTE9XTElTVF9DT01QQVRJQklMSVRZX0JQIiwiRk9SQklEREVOX0ZJRUxEX0NPVU5UIl0sCiAgICAgICJzb3VyY2VHYXRlcyI6WwogICAgICAgIHsiZGVmaW5pdGlvbktpbmQiOiJzb3VyY2UtZ2F0ZSIsIm1ldHJpY0lkIjoiVkFMSURfUkVDT1JEX1JBVEVfQlAiLCJzb3VyY2VJZCI6IlNSQy1QMS1BQ0FERU1JQy0wMDEiLCJnYXRlSWQiOiJtYW5pZmVzdC1yZWNvbmNpbGUiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL1NSQy1QMS1BQ0FERU1JQy0wMDEvbWFuaWZlc3QtcmVjb25jaWxlIiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY292ZXJhZ2UiLCJjYWxjdWxhdGlvbiI6eyJraW5kIjoicmF0aW8iLCJudW1lcmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoibWFuaWZlc3QtcmVjb25jaWxlZC1yZWNvcmRzIn0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6Im1hbmlmZXN0LWRlY2xhcmVkLXJlY29yZC1jb3VudCJ9fSwidW5pdCI6ImJhc2lzLXBvaW50Iiwib3BlcmF0b3IiOiI9IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjEsInRocmVzaG9sZERlbm9taW5hdG9yIjoxLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6ImFsd2F5cyJ9LCJvd25lciI6IuaVmeWKoeWtpuS4muaVsOaNriBvd25lciIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9LAogICAgICAgIHsiZGVmaW5pdGlvbktpbmQiOiJzb3VyY2UtZ2F0ZSIsIm1ldHJpY0lkIjoiU09VUkNFX0NPTlRJTlVJVFlfR0FURSIsInNvdXJjZUlkIjoiU1JDLVAxLUFDQURFTUlDLTAwMSIsImdhdGVJZCI6InNlYWwtY29ycmVjdGlvbi1jaGFpbi1jb21wbGV0ZSIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU1JDLVAxLUFDQURFTUlDLTAwMS9zZWFsLWNvcnJlY3Rpb24tY2hhaW4tY29tcGxldGUiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb250aW51aXR5IiwiY2FsY3VsYXRpb24iOnsia2luZCI6ImNvdW50IiwibnVtZXJhdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6ImNoYWluLXZpb2xhdGlvbnMifSwiZGVub21pbmF0b3IiOnsia2luZCI6ImNvbnN0YW50IiwidmFsdWUiOjF9fSwidW5pdCI6ImNvdW50Iiwib3BlcmF0b3IiOiI9IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjAsInRocmVzaG9sZERlbm9taW5hdG9yIjoxLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6ImFsd2F5cyJ9LCJvd25lciI6IuaVmeWKoeWtpuS4muaVsOaNriBvd25lciIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9CiAgICAgIF0sCiAgICAgICJmcmVzaG5lc3NMYW5lcyI6W3sibGFuZUlkIjoic2VhbGVkLWJhdGNoIiwidW5pdCI6InNlYWxlZC1iYXRjaCIsInRpbWV6b25lIjoiQXNpYS9TaGFuZ2hhaSIsImluY2x1c2l2ZSI6dHJ1ZSwiYWxsb3dOb0FjdGl2aXR5IjpmYWxzZSwicnVsZSI6eyJraW5kIjoibG9jYWwtY3V0b2ZmIiwibGF0ZXJGaWVsZCI6Im1hbmlmZXN0LnJlY2VpdmVkQXQiLCJlYXJsaWVyRmllbGQiOiJtYW5pZmVzdC5zY2hlZHVsZWREdWVBdCIsImR1ZUxvY2FsVGltZSI6IjA4OjAwOjAwIiwiZGF5T2Zmc2V0IjoxfX0seyJsYW5lSWQiOiJjb3JyZWN0aW9uLXJlY29yZCIsInVuaXQiOiJyZWNvcmQiLCJ0aW1lem9uZSI6IkFzaWEvU2hhbmdoYWkiLCJpbmNsdXNpdmUiOnRydWUsImFsbG93Tm9BY3Rpdml0eSI6ZmFsc2UsInJ1bGUiOnsia2luZCI6ImR1cmF0aW9uIiwibGF0ZXJGaWVsZCI6Im1hbmlmZXN0LnJlY2VpdmVkQXQiLCJlYXJsaWVyRmllbGQiOiJtYW5pZmVzdC5zb3VyY2VPY2N1cnJlZEF0IiwibWF4RHVyYXRpb25NaWxsaXNlY29uZHMiOjE0NDAwMDAwfX1dCiAgICB9LAogICAgewogICAgICAic291cmNlSWQiOiJTUkMtUDEtQ0FSRS1MSVNULTAwMSIsIm93bmVyIjoi5YWz54ix5ZCN5Y2VIG93bmVyIiwic2NoZW1hQmluZGluZyI6eyJwYXRoIjoiY29udHJhY3RzL2RhdGEtY2F0YWxvZy9zb3VyY2VzL3NyYy1wMS1jYXJlLWxpc3QtMDAxLTEuMS4wLnNjaGVtYS5qc29uIiwidmVyc2lvbiI6IkNBUkUtTElTVC1TTElDRS0xLjEuMCIsInJhd1NoYTI1NiI6ImQ0ZWQ0Y2EyODJiMTJjMjMwMjQ2MDA2MzU1NDY3MjliZTY1NTBmZjFiM2M3NzMyMDlkZDNjMjYzNGE0YzllMDEiLCJjYW5vbmljYWxEaWdlc3QiOiJzaGEyNTY6YWQ1ZGFiZWVkMzZiZjAyZDNmNGZlNTc2ZjA3Njk3YjU2MzI1MmE1ZDM3MTY4MTMwZmYyMTRjYzg2OGU4MTVhZCJ9LAogICAgICAiYXBwbGljYWJsZUNvbW1vbk1ldHJpY0lkcyI6WyJQUklNQVJZX0tFWV9DT01QTEVURU5FU1NfQlAiLCJSRVFVSVJFRF9GSUVMRF9WQUxJRElUWV9CUCIsIlZBTElEX1JFQ09SRF9SQVRFX0JQIiwiQ09SRV9GSUVMRF9DT1ZFUkFHRV9CUCIsIkZSRVNITkVTU19XSVRISU5fU0xPX0JQIiwiVU5SRVNPTFZFRF9JTlRFUlZBTF9DT05GTElDVF9DT1VOVCIsIkRVUExJQ0FURV9CVVNJTkVTU19LRVlfQ09VTlQiLCJWRVJTSU9OX1JFR1JFU1NJT05fQ09VTlQiLCJTT1VSQ0VfQ09OVElOVUlUWV9HQVRFIiwiU0NIRU1BX0FMTE9XTElTVF9DT01QQVRJQklMSVRZX0JQIiwiRk9SQklEREVOX0ZJRUxEX0NPVU5UIl0sCiAgICAgICJzb3VyY2VHYXRlcyI6WwogICAgICAgIHsiZGVmaW5pdGlvbktpbmQiOiJzb3VyY2UtZ2F0ZSIsIm1ldHJpY0lkIjoiQ09SRV9GSUVMRF9DT1ZFUkFHRV9CUCIsInNvdXJjZUlkIjoiU1JDLVAxLUNBUkUtTElTVC0wMDEiLCJnYXRlSWQiOiJjYXJlLWxpc3QtY29yZS1maWVsZC1ncm91cCIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU1JDLVAxLUNBUkUtTElTVC0wMDEvY2FyZS1saXN0LWNvcmUtZmllbGQtZ3JvdXAiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb3ZlcmFnZSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJyZWNvcmRzLXBhc3Npbmctc291cmNlLWZpZWxkLWdyb3VwIn0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6InJlY29yZHMtYXBwbGljYWJsZS10by1zb3VyY2UtZmllbGQtZ3JvdXAifX0sInVuaXQiOiJiYXNpcy1wb2ludCIsIm9wZXJhdG9yIjoiPSIsImJvdW5kYXJ5IjoiaW5jbHVzaXZlIiwidGhyZXNob2xkTnVtZXJhdG9yIjoxLCJ0aHJlc2hvbGREZW5vbWluYXRvciI6MSwiZGVub21pbmF0b3JaZXJvQmVoYXZpb3IiOiJldmFsdWF0aW9uLWVycm9yIiwidmFsdWVTY2FsZSI6MCwicm91bmRpbmdNb2RlIjoiSEFMRl9VUCIsImNvbXBhcmlzb25TdGFnZSI6InByZS1yb3VuZGluZy1jcm9zcy1tdWx0aXBseSIsImFwcGxpY2FiaWxpdHkiOnsicHJlZGljYXRlSWQiOiJzb3VyY2UtZmllbGQtZ3JvdXAtcHJlc2VudCJ9LCJmaWVsZFNldCI6WyJwdXJwb3NlIiwiZXZpZGVuY2VSZWYiLCJlZmZlY3RpdmVGcm9tIiwiZWZmZWN0aXZlVG8iLCJhcHByb3ZhbFZlcnNpb24iXSwib3duZXIiOiLlhbPniLHlkI3ljZUgb3duZXIiLCJhcHByb3ZhbFJlZiI6IkFVVEgtMjAyNi0wOC0wOC0wMDEiLCJlZmZlY3RpdmVBdCI6IjIwMjYtMDgtMDlUMTA6MDI6MjIrMDg6MDAiLCJldmlkZW5jZVJlZiI6InN0b3J5Oi8vMi4zL0RFQy0wMTkvQVVUSC0yMDI2LTA4LTA4LTAwMSIsImhhcmRHYXRlIjp0cnVlfQogICAgICBdLAogICAgICAiZnJlc2huZXNzTGFuZXMiOlt7ImxhbmVJZCI6ImNoYW5nZS1yZWNvcmQiLCJ1bml0IjoicmVjb3JkIiwidGltZXpvbmUiOiJBc2lhL1NoYW5naGFpIiwiaW5jbHVzaXZlIjp0cnVlLCJhbGxvd05vQWN0aXZpdHkiOmZhbHNlLCJydWxlIjp7ImtpbmQiOiJkdXJhdGlvbiIsImxhdGVyRmllbGQiOiJtYW5pZmVzdC5yZWNlaXZlZEF0IiwiZWFybGllckZpZWxkIjoibWFuaWZlc3Quc291cmNlT2NjdXJyZWRBdCIsIm1heER1cmF0aW9uTWlsbGlzZWNvbmRzIjozNjAwMDAwfX0seyJsYW5lSWQiOiJyZXZva2UtZXhwaXJ5LXJlY29yZCIsInVuaXQiOiJyZWNvcmQiLCJ0aW1lem9uZSI6IkFzaWEvU2hhbmdoYWkiLCJpbmNsdXNpdmUiOnRydWUsImFsbG93Tm9BY3Rpdml0eSI6ZmFsc2UsInJ1bGUiOnsia2luZCI6ImR1cmF0aW9uIiwibGF0ZXJGaWVsZCI6Im1hbmlmZXN0LnJlY2VpdmVkQXQiLCJlYXJsaWVyRmllbGQiOiJtYW5pZmVzdC5zb3VyY2VPY2N1cnJlZEF0IiwibWF4RHVyYXRpb25NaWxsaXNlY29uZHMiOjkwMDAwMH19XQogICAgfSwKICAgIHsKICAgICAgInNvdXJjZUlkIjoiU1JDLVAxLVBTWUNILURFSUQtMDAxIiwib3duZXIiOiLlv4PnkIbohLHmlY/moIfnrb4gb3duZXIiLCJzY2hlbWFCaW5kaW5nIjp7InBhdGgiOiJjb250cmFjdHMvZGF0YS1jYXRhbG9nL3NvdXJjZXMvc3JjLXAxLXBzeWNoLWRlaWQtMDAxLnNjaGVtYS5qc29uIiwidmVyc2lvbiI6IlBTWUNILURFSUQtU0xJQ0UtMS4wLjAiLCJyYXdTaGEyNTYiOiI3Yzg0OWQ5ZWRhOTczMzRhNWM4ODU3Njc3YjQyYTc0NWIxYTdkNjEwNjViOTY1MDZiYTljMzQ4OTUyZmQ0MTljIiwiY2Fub25pY2FsRGlnZXN0Ijoic2hhMjU2OjI4Y2I2YzU2YWMwMDRiZjM4Njc3NmU4OTc4NDdmZjBkMDg3OTcyZjEyYTUxMTFlOGM1ZGJhZTIyMGE4NTRjZWEifSwKICAgICAgImFwcGxpY2FibGVDb21tb25NZXRyaWNJZHMiOlsiUFJJTUFSWV9LRVlfQ09NUExFVEVORVNTX0JQIiwiUkVRVUlSRURfRklFTERfVkFMSURJVFlfQlAiLCJWQUxJRF9SRUNPUkRfUkFURV9CUCIsIkNPUkVfRklFTERfQ09WRVJBR0VfQlAiLCJGUkVTSE5FU1NfV0lUSElOX1NMT19CUCIsIlVOUkVTT0xWRURfSU5URVJWQUxfQ09ORkxJQ1RfQ09VTlQiLCJEVVBMSUNBVEVfQlVTSU5FU1NfS0VZX0NPVU5UIiwiVkVSU0lPTl9SRUdSRVNTSU9OX0NPVU5UIiwiU09VUkNFX0NPTlRJTlVJVFlfR0FURSIsIlNDSEVNQV9BTExPV0xJU1RfQ09NUEFUSUJJTElUWV9CUCIsIkZPUkJJRERFTl9GSUVMRF9DT1VOVCJdLAogICAgICAic291cmNlR2F0ZXMiOlsKICAgICAgICB7ImRlZmluaXRpb25LaW5kIjoic291cmNlLWdhdGUiLCJtZXRyaWNJZCI6IkNPUkVfRklFTERfQ09WRVJBR0VfQlAiLCJzb3VyY2VJZCI6IlNSQy1QMS1QU1lDSC1ERUlELTAwMSIsImdhdGVJZCI6InBzeWNoLWNvcmUtZmllbGQtYWxsb3dsaXN0IiwiZm9ybXVsYUlkIjoiUU1EUC0xLjAuMC9TUkMtUDEtUFNZQ0gtREVJRC0wMDEvcHN5Y2gtY29yZS1maWVsZC1hbGxvd2xpc3QiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb3ZlcmFnZSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJhbGxvd2xpc3QtdmFsaWQtcmVjb3JkcyJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJyZWNvcmRzLWFwcGxpY2FibGUtdG8tc291cmNlLWZpZWxkLWdyb3VwIn19LCJ1bml0IjoiYmFzaXMtcG9pbnQiLCJvcGVyYXRvciI6Ij0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6MSwidGhyZXNob2xkRGVub21pbmF0b3IiOjEsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoic291cmNlLWZpZWxkLWdyb3VwLXByZXNlbnQifSwiZmllbGRTZXQiOlsiY2F0ZWdvcnkiLCJwdXJwb3NlIiwiZWZmZWN0aXZlRnJvbSIsImVmZmVjdGl2ZVRvIl0sIm93bmVyIjoi5b+D55CG6ISx5pWP5qCH562+IG93bmVyIiwiYXBwcm92YWxSZWYiOiJBVVRILTIwMjYtMDgtMDgtMDAxIiwiZWZmZWN0aXZlQXQiOiIyMDI2LTA4LTA5VDEwOjAyOjIyKzA4OjAwIiwiZXZpZGVuY2VSZWYiOiJzdG9yeTovLzIuMy9ERUMtMDE5L0FVVEgtMjAyNi0wOC0wOC0wMDEiLCJoYXJkR2F0ZSI6dHJ1ZX0sCiAgICAgICAgeyJkZWZpbml0aW9uS2luZCI6InNvdXJjZS1nYXRlIiwibWV0cmljSWQiOiJGT1JCSURERU5fRklFTERfQ09VTlQiLCJzb3VyY2VJZCI6IlNSQy1QMS1QU1lDSC1ERUlELTAwMSIsImdhdGVJZCI6ImZvcmJpZGRlbi1jb250ZW50LWZpZWxkLWNvdW50IiwiZm9ybXVsYUlkIjoiUU1EUC0xLjAuMC9TUkMtUDEtUFNZQ0gtREVJRC0wMDEvZm9yYmlkZGVuLWNvbnRlbnQtZmllbGQtY291bnQiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJwcml2YWN5IiwiY2FsY3VsYXRpb24iOnsia2luZCI6ImNvdW50IiwibnVtZXJhdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6ImZvcmJpZGRlbi1jb250ZW50LWhpdHMifSwiZGVub21pbmF0b3IiOnsia2luZCI6ImNvbnN0YW50IiwidmFsdWUiOjF9fSwidW5pdCI6ImNvdW50Iiwib3BlcmF0b3IiOiI9IiwiYm91bmRhcnkiOiJpbmNsdXNpdmUiLCJ0aHJlc2hvbGROdW1lcmF0b3IiOjAsInRocmVzaG9sZERlbm9taW5hdG9yIjoxLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6ImFsd2F5cyJ9LCJvd25lciI6IuW/g+eQhuiEseaVj+agh+etviBvd25lciIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9CiAgICAgIF0sCiAgICAgICJmcmVzaG5lc3NMYW5lcyI6W3sibGFuZUlkIjoiYmF0Y2gtcGFydGl0aW9uIiwidW5pdCI6InNjaGVkdWxlZC1wYXJ0aXRpb24iLCJ0aW1lem9uZSI6IkFzaWEvU2hhbmdoYWkiLCJpbmNsdXNpdmUiOnRydWUsImFsbG93Tm9BY3Rpdml0eSI6ZmFsc2UsInJ1bGUiOnsia2luZCI6ImxvY2FsLWN1dG9mZiIsImxhdGVyRmllbGQiOiJtYW5pZmVzdC5yZWNlaXZlZEF0IiwiZWFybGllckZpZWxkIjoibWFuaWZlc3Quc2NoZWR1bGVkRHVlQXQiLCJkdWVMb2NhbFRpbWUiOiIwODowMDowMCIsImRheU9mZnNldCI6MX19XQogICAgfSwKICAgIHsKICAgICAgInNvdXJjZUlkIjoiU1JDLVAxLUFJRC0wMDEiLCJvd25lciI6Iui1hOWKqeaVsOaNriBvd25lciIsInNjaGVtYUJpbmRpbmciOnsicGF0aCI6ImNvbnRyYWN0cy9kYXRhLWNhdGFsb2cvc291cmNlcy9zcmMtcDEtYWlkLTAwMS5zY2hlbWEuanNvbiIsInZlcnNpb24iOiJBSUQtU0xJQ0UtMS4wLjAiLCJyYXdTaGEyNTYiOiJkYzA5NTlmZGFkYjIyNWIwYmRlZjM3ZGYxNjE3OGFkZDMxNDExOWE1NzUwMmFkZGExMmNlYTg3NmEyNzI4ZTVlIiwiY2Fub25pY2FsRGlnZXN0Ijoic2hhMjU2OmQ2OTFjZTU5OGViYWYwNTgxYjMwYzk5MjJlNGNkZGRkYmE3ODYzYWE0Yzk5ZDMwYjNlN2VhMzMzYmMzZTM3YjcifSwKICAgICAgImFwcGxpY2FibGVDb21tb25NZXRyaWNJZHMiOlsiUFJJTUFSWV9LRVlfQ09NUExFVEVORVNTX0JQIiwiUkVRVUlSRURfRklFTERfVkFMSURJVFlfQlAiLCJWQUxJRF9SRUNPUkRfUkFURV9CUCIsIkNPUkVfRklFTERfQ09WRVJBR0VfQlAiLCJGUkVTSE5FU1NfV0lUSElOX1NMT19CUCIsIlVOUkVTT0xWRURfSU5URVJWQUxfQ09ORkxJQ1RfQ09VTlQiLCJEVVBMSUNBVEVfQlVTSU5FU1NfS0VZX0NPVU5UIiwiVkVSU0lPTl9SRUdSRVNTSU9OX0NPVU5UIiwiU09VUkNFX0NPTlRJTlVJVFlfR0FURSIsIlNDSEVNQV9BTExPV0xJU1RfQ09NUEFUSUJJTElUWV9CUCIsIkZPUkJJRERFTl9GSUVMRF9DT1VOVCJdLAogICAgICAic291cmNlR2F0ZXMiOlsKICAgICAgICB7ImRlZmluaXRpb25LaW5kIjoic291cmNlLWdhdGUiLCJtZXRyaWNJZCI6IkNPUkVfRklFTERfQ09WRVJBR0VfQlAiLCJzb3VyY2VJZCI6IlNSQy1QMS1BSUQtMDAxIiwiZ2F0ZUlkIjoiYWlkLWNvcmUtZmllbGQtZ3JvdXAiLCJmb3JtdWxhSWQiOiJRTURQLTEuMC4wL1NSQy1QMS1BSUQtMDAxL2FpZC1jb3JlLWZpZWxkLWdyb3VwIiwiZm9ybXVsYVZlcnNpb24iOiIxLjAuMCIsImNhdGVnb3J5IjoiY292ZXJhZ2UiLCJjYWxjdWxhdGlvbiI6eyJraW5kIjoicmF0aW8iLCJudW1lcmF0b3IiOnsia2luZCI6Im1lYXN1cmVkIiwib3BlcmFuZElkIjoicmVjb3Jkcy1wYXNzaW5nLXNvdXJjZS1maWVsZC1ncm91cCJ9LCJkZW5vbWluYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJyZWNvcmRzLWFwcGxpY2FibGUtdG8tc291cmNlLWZpZWxkLWdyb3VwIn19LCJ1bml0IjoiYmFzaXMtcG9pbnQiLCJvcGVyYXRvciI6Ij0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6MSwidGhyZXNob2xkRGVub21pbmF0b3IiOjEsImRlbm9taW5hdG9yWmVyb0JlaGF2aW9yIjoiZXZhbHVhdGlvbi1lcnJvciIsInZhbHVlU2NhbGUiOjAsInJvdW5kaW5nTW9kZSI6IkhBTEZfVVAiLCJjb21wYXJpc29uU3RhZ2UiOiJwcmUtcm91bmRpbmctY3Jvc3MtbXVsdGlwbHkiLCJhcHBsaWNhYmlsaXR5Ijp7InByZWRpY2F0ZUlkIjoic291cmNlLWZpZWxkLWdyb3VwLXByZXNlbnQifSwiZmllbGRTZXQiOlsicHVycG9zZSIsImVmZmVjdGl2ZUZyb20iLCJlZmZlY3RpdmVUbyIsImFwcHJvdmFsVmVyc2lvbiJdLCJvd25lciI6Iui1hOWKqeaVsOaNriBvd25lciIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9CiAgICAgIF0sCiAgICAgICJmcmVzaG5lc3NMYW5lcyI6W3sibGFuZUlkIjoiYmF0Y2gtcGFydGl0aW9uIiwidW5pdCI6InNjaGVkdWxlZC1wYXJ0aXRpb24iLCJ0aW1lem9uZSI6IkFzaWEvU2hhbmdoYWkiLCJpbmNsdXNpdmUiOnRydWUsImFsbG93Tm9BY3Rpdml0eSI6ZmFsc2UsInJ1bGUiOnsia2luZCI6ImxvY2FsLWN1dG9mZiIsImxhdGVyRmllbGQiOiJtYW5pZmVzdC5yZWNlaXZlZEF0IiwiZWFybGllckZpZWxkIjoibWFuaWZlc3Quc2NoZWR1bGVkRHVlQXQiLCJkdWVMb2NhbFRpbWUiOiIwODowMDowMCIsImRheU9mZnNldCI6MX19LHsibGFuZUlkIjoicmV2b2tlLXJlY29yZCIsInVuaXQiOiJyZWNvcmQiLCJ0aW1lem9uZSI6IkFzaWEvU2hhbmdoYWkiLCJpbmNsdXNpdmUiOnRydWUsImFsbG93Tm9BY3Rpdml0eSI6ZmFsc2UsInJ1bGUiOnsia2luZCI6ImR1cmF0aW9uIiwibGF0ZXJGaWVsZCI6Im1hbmlmZXN0LnJlY2VpdmVkQXQiLCJlYXJsaWVyRmllbGQiOiJtYW5pZmVzdC5zb3VyY2VPY2N1cnJlZEF0IiwibWF4RHVyYXRpb25NaWxsaXNlY29uZHMiOjE0NDAwMDAwfX1dCiAgICB9LAogICAgewogICAgICAic291cmNlSWQiOiJTUkMtUDEtV09SSy1WSVNJVC0wMDEiLCJvd25lciI6Iui+heWvvOWRmOW3peS9nOe6quWuniBvd25lciIsInNjaGVtYUJpbmRpbmciOnsicGF0aCI6ImNvbnRyYWN0cy9kYXRhLWNhdGFsb2cvc291cmNlcy9zcmMtcDEtd29yay12aXNpdC0wMDEuc2NoZW1hLmpzb24iLCJ2ZXJzaW9uIjoiV09SSy1WSVNJVC1TTElDRS0xLjAuMCIsInJhd1NoYTI1NiI6IjA1YzhiZmE3ODRlYzVmMDVkODc4YTY0MWRlMWQyYzk5OWEwZDQ0MjBmMGZkYjVlNmQ5MzY4ZjgxN2UwODIxZmIiLCJjYW5vbmljYWxEaWdlc3QiOiJzaGEyNTY6NjM5M2M1NDFkZTE1M2NkYTJmYTZkZGFjOGI5MTQ5M2U5YzY1NzVkMTc1ZTQ1NGRiYjJiZjVmY2QxN2M2ZDFmNyJ9LAogICAgICAiYXBwbGljYWJsZUNvbW1vbk1ldHJpY0lkcyI6WyJQUklNQVJZX0tFWV9DT01QTEVURU5FU1NfQlAiLCJSRVFVSVJFRF9GSUVMRF9WQUxJRElUWV9CUCIsIlZBTElEX1JFQ09SRF9SQVRFX0JQIiwiRlJFU0hORVNTX1dJVEhJTl9TTE9fQlAiLCJVTlJFU09MVkVEX0lOVEVSVkFMX0NPTkZMSUNUX0NPVU5UIiwiRFVQTElDQVRFX0JVU0lORVNTX0tFWV9DT1VOVCIsIlZFUlNJT05fUkVHUkVTU0lPTl9DT1VOVCIsIlNPVVJDRV9DT05USU5VSVRZX0dBVEUiLCJTQ0hFTUFfQUxMT1dMSVNUX0NPTVBBVElCSUxJVFlfQlAiLCJGT1JCSURERU5fRklFTERfQ09VTlQiXSwKICAgICAgInNvdXJjZUdhdGVzIjpbCiAgICAgICAgeyJkZWZpbml0aW9uS2luZCI6InNvdXJjZS1nYXRlIiwibWV0cmljSWQiOiJSRVFVSVJFRF9GSUVMRF9WQUxJRElUWV9CUCIsInNvdXJjZUlkIjoiU1JDLVAxLVdPUkstVklTSVQtMDAxIiwiZ2F0ZUlkIjoid29yay12aXNpdC1yZXF1aXJlZC1maWVsZC1ncm91cCIsImZvcm11bGFJZCI6IlFNRFAtMS4wLjAvU1JDLVAxLVdPUkstVklTSVQtMDAxL3dvcmstdmlzaXQtcmVxdWlyZWQtZmllbGQtZ3JvdXAiLCJmb3JtdWxhVmVyc2lvbiI6IjEuMC4wIiwiY2F0ZWdvcnkiOiJjb3ZlcmFnZSIsImNhbGN1bGF0aW9uIjp7ImtpbmQiOiJyYXRpbyIsIm51bWVyYXRvciI6eyJraW5kIjoibWVhc3VyZWQiLCJvcGVyYW5kSWQiOiJyZWNvcmRzLXBhc3Npbmctc291cmNlLWZpZWxkLWdyb3VwIn0sImRlbm9taW5hdG9yIjp7ImtpbmQiOiJtZWFzdXJlZCIsIm9wZXJhbmRJZCI6InJlY29yZHMtYXBwbGljYWJsZS10by1zb3VyY2UtZmllbGQtZ3JvdXAifX0sInVuaXQiOiJiYXNpcy1wb2ludCIsIm9wZXJhdG9yIjoiPj0iLCJib3VuZGFyeSI6ImluY2x1c2l2ZSIsInRocmVzaG9sZE51bWVyYXRvciI6OTk1MCwidGhyZXNob2xkRGVub21pbmF0b3IiOjEwMDAwLCJkZW5vbWluYXRvclplcm9CZWhhdmlvciI6ImV2YWx1YXRpb24tZXJyb3IiLCJ2YWx1ZVNjYWxlIjowLCJyb3VuZGluZ01vZGUiOiJIQUxGX1VQIiwiY29tcGFyaXNvblN0YWdlIjoicHJlLXJvdW5kaW5nLWNyb3NzLW11bHRpcGx5IiwiYXBwbGljYWJpbGl0eSI6eyJwcmVkaWNhdGVJZCI6InNvdXJjZS1maWVsZC1ncm91cC1wcmVzZW50In0sImZpZWxkU2V0IjpbInZpc2l0SWQiLCJ2aXNpdG9yUmVmIiwibG9jYWxEYXRlIiwiYXJlYUNvZGUiLCJ2aXNpdE1vZGUiLCJjb250cm9sbGVkU3VtbWFyeUNvZGUiLCJ3b3JrVmlzaXRQb2xpY3lWZXJzaW9uIiwic291cmNlVmVyc2lvbiJdLCJvd25lciI6Iui+heWvvOWRmOW3peS9nOe6quWuniBvd25lciIsImFwcHJvdmFsUmVmIjoiQVVUSC0yMDI2LTA4LTA4LTAwMSIsImVmZmVjdGl2ZUF0IjoiMjAyNi0wOC0wOVQxMDowMjoyMiswODowMCIsImV2aWRlbmNlUmVmIjoic3Rvcnk6Ly8yLjMvREVDLTAxOS9BVVRILTIwMjYtMDgtMDgtMDAxIiwiaGFyZEdhdGUiOnRydWV9CiAgICAgIF0sCiAgICAgICJmcmVzaG5lc3NMYW5lcyI6W3sibGFuZUlkIjoic3VibWlzc2lvbi1wYXJ0aXRpb24iLCJ1bml0Ijoic2NoZWR1bGVkLXBhcnRpdGlvbiIsInRpbWV6b25lIjoiQXNpYS9TaGFuZ2hhaSIsImluY2x1c2l2ZSI6dHJ1ZSwiYWxsb3dOb0FjdGl2aXR5IjpmYWxzZSwicnVsZSI6eyJraW5kIjoibG9jYWwtY3V0b2ZmIiwibGF0ZXJGaWVsZCI6Im1hbmlmZXN0LnJlY2VpdmVkQXQiLCJlYXJsaWVyRmllbGQiOiJtYW5pZmVzdC5zY2hlZHVsZWREdWVBdCIsImR1ZUxvY2FsVGltZSI6IjA4OjAwOjAwIiwiZGF5T2Zmc2V0IjoxfX1dCiAgICB9CiAgXSwKICAibm9uTWV0cmljQ29uc3RyYWludHMiOiBbCiAgICB7ImNvbnN0cmFpbnRJZCI6Im5vdC1lY29uLWhpdC1ldmlkZW5jZSIsImtpbmQiOiJjb25zdW1lci1wdXJwb3NlLWRlbnkiLCJzb3VyY2VJZCI6IlNSQy1QMS1BSUQtMDAxIiwiZGVuaWVkQ29uc3VtZXJQdXJwb3NlIjoiRUNPTi0wMTItaGl0LWV2aWRlbmNlIn0sCiAgICB7ImNvbnN0cmFpbnRJZCI6Im5vdC1zdHVkZW50LWV2YWx1YXRpb24tZmVhdHVyZSIsImtpbmQiOiJjb25zdW1lci1wdXJwb3NlLWRlbnkiLCJzb3VyY2VJZCI6IlNSQy1QMS1XT1JLLVZJU0lULTAwMSIsImRlbmllZENvbnN1bWVyUHVycG9zZSI6InN0dWRlbnQtZXZhbHVhdGlvbi1mZWF0dXJlIn0KICBdLAogICJvdmVyYWxsUmVzdWx0IjogewogICAgIm9wZXJhdG9yIjoiQU5EIiwKICAgICJtaW5pbXVtQXBwbGljYWJsZUhhcmRHYXRlcyI6MSwKICAgICJldmFsdWF0aW9uRXJyb3JSZXN1bHQiOiJzZWFsZWQvZXZhbHVhdGlvbi1lcnJvciIsCiAgICAiZmFpbGVkUmVzdWx0IjoicXVhbGl0eS1mYWlsZWQiLAogICAgInBhc3NlZFJlc3VsdCI6InF1YWxpdHktcGFzc2VkIgogIH0KfQo=', 'base64')
)
insert into ingestion_quality.iq_frozen_qmdp_policy
  (singleton, profile_version, raw_digest, canonical_digest, raw_utf8, policy)
select true, 'QMDP-1.0.0',
       'sha256:' || encode(sha256(value), 'hex'),
       'sha256:' || encode(sha256(convert_to(
         ingestion_quality.iq_json_canonical(convert_from(value, 'UTF8')::jsonb),
         'UTF8')), 'hex'),
       value, convert_from(value, 'UTF8')::jsonb
  from iq_raw;

create function ingestion_quality.iq_qmdp_source(requested_source_id varchar)
returns jsonb
language plpgsql
stable
security definer
strict
set search_path = pg_catalog
as $$
declare
    iq_policy jsonb;
    iq_source jsonb;
    iq_count integer;
begin
    select policy into strict iq_policy
      from ingestion_quality.iq_frozen_qmdp_policy
     where singleton;
    select count(*), min(iq_value::text)::jsonb
      into iq_count, iq_source
      from jsonb_array_elements(iq_policy -> 'sources') source(iq_value)
     where iq_value ->> 'sourceId' = requested_source_id;
    if iq_count <> 1 then
        raise exception using
            errcode = 'invalid_parameter_value',
            message = 'INGESTION_QUALITY_QMDP_SOURCE_INVALID';
    end if;
    return iq_source;
end
$$;

create function ingestion_quality.iq_qmdp_ordered_definitions(
    requested_source_id varchar)
returns table (
    metric_ordinal integer,
    definition jsonb,
    fixed_applicable boolean)
language sql
stable
security definer
strict
set search_path = pg_catalog
as $$
with iq_policy as (
  select policy
    from ingestion_quality.iq_frozen_qmdp_policy
   where singleton
), iq_source as (
  select ingestion_quality.iq_qmdp_source(requested_source_id) as value
), iq_definitions as (
  select 0 as iq_scope, iq_ordinal, iq_value
    from iq_policy
    cross join lateral jsonb_array_elements(policy -> 'commonMetrics')
      with ordinality metric(iq_value, iq_ordinal)
   where exists (
     select 1
       from iq_source
       cross join lateral jsonb_array_elements_text(
         value -> 'applicableCommonMetricIds') applicable(iq_metric_id)
      where iq_metric_id = iq_value ->> 'metricId')
  union all
  select 1, iq_ordinal, iq_value
    from iq_source
    cross join lateral jsonb_array_elements(value -> 'sourceGates')
      with ordinality gate(iq_value, iq_ordinal)
), iq_ordered as (
  select row_number() over (order by iq_scope, iq_ordinal) - 1 as iq_index,
         iq_value
    from iq_definitions
)
select iq_index::integer,
       iq_value,
       case iq_value #>> '{applicability,predicateId}'
         when 'always' then true
         when 'source-in-approved-set' then exists (
           select 1
             from jsonb_array_elements_text(
               iq_value #> '{applicability,sourceIds}') allowed(iq_source_id)
            where iq_source_id = requested_source_id)
         when 'source-field-group-present' then
           coalesce(jsonb_array_length(iq_value -> 'fieldSet') > 0, false)
           or exists (
             select 1
               from iq_source
               cross join lateral jsonb_array_elements(value -> 'sourceGates')
                 gate(iq_gate)
              where iq_gate ->> 'metricId' = iq_value ->> 'metricId'
                and coalesce(jsonb_array_length(iq_gate -> 'fieldSet') > 0, false))
         when 'overlap-records-present' then null
         else null
       end
 from iq_ordered
 order by iq_index
$$;

create function ingestion_quality.iq_require_production_watermark(
    requested_source_id varchar,
    requested_watermark_utf8 bytea)
returns void
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
declare
    iq_value text;
    iq_prefix text;
    iq_date_text text;
    iq_date date;
    iq_year integer;
begin
    if requested_source_id is null
       or requested_watermark_utf8 is null
       or ingestion_quality.iq_utf8_scalar_count(requested_watermark_utf8)
            not between 1 and 512
       or position(decode('00', 'hex') in requested_watermark_utf8) > 0 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
    begin
        iq_value := convert_from(requested_watermark_utf8, 'UTF8');
    exception
        when character_not_in_repertoire then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end;
    if iq_value ~ '^sha256:[0-9a-f]{64}$' then
        return;
    end if;
    iq_prefix := lower(requested_source_id) || '@';
    if not starts_with(iq_value, iq_prefix) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
    iq_date_text := substring(iq_value from char_length(iq_prefix) + 1);
    if iq_date_text !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
    iq_year := substring(iq_date_text from 1 for 4)::integer;
    if iq_year not between 1 and 9999 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
    begin
        iq_date := make_date(
            iq_year,
            substring(iq_date_text from 6 for 2)::integer,
            substring(iq_date_text from 9 for 2)::integer);
    exception
        when datetime_field_overflow then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end;
    if to_char(iq_date, 'YYYY-MM-DD') <> iq_date_text then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
end
$$;

create function ingestion_quality.iq_require_production_impact_scope(
    requested_source_id varchar,
    requested_scope_code_utf8 bytea)
returns void
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
declare
    iq_value text;
begin
    if requested_source_id is null
       or requested_scope_code_utf8 is null
       or ingestion_quality.iq_utf8_scalar_count(requested_scope_code_utf8)
            not between 1 and 64
       or position(decode('00', 'hex') in requested_scope_code_utf8) > 0 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_IMPACT_SCOPE_INVALID';
    end if;
    begin
        iq_value := convert_from(requested_scope_code_utf8, 'UTF8');
    exception
        when character_not_in_repertoire then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_IMPACT_SCOPE_INVALID';
    end;
    if iq_value !~ '^[A-Z][A-Z0-9_]{1,63}$'
       or not exists (
         select 1
           from ingestion_quality.iq_qmdp_ordered_definitions(requested_source_id) expected
          where expected.definition ->> 'metricId' = iq_value) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_IMPACT_SCOPE_INVALID';
    end if;
end
$$;

create function ingestion_quality.iq_expected_sealed_contract()
returns jsonb
language sql
immutable
set search_path = pg_catalog
as $$
select jsonb_build_object(
  'qmdpProfileVersion', 'QMDP-1.0.0',
  'qmdpPolicyRawDigest',
    'sha256:1e7703748a7bda56034ab189700a82a503674d364ecac67c1eb35fe6ee8c0d84',
  'qmdpPolicyCanonicalDigest',
    'sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8',
  'qmdpContractLockVersion', 'EXECUTABLE-QUALITY-CONTRACT-LOCK-1.0.0',
  'qmdpContractLockRawDigest',
    'sha256:b93d5547e6b28aa7281f736bd2440800eea590987d68ae8ab28ffd6a225cdb6f',
  'qmdpContractLockCanonicalDigest',
    'sha256:386f02acbdb9310fbe93e155e021023154005b2e9e01673f93c2ecdc881f8fce',
  'qmdpAuthorityRef', 'AUTH-2026-08-08-001',
  'qmdpApprovalRef', 'AUTH-2026-08-08-001',
  'qmdpEffectiveAt', '2026-08-09T02:02:22Z',
  'qshmProfileVersion', 'QSHM-1.0.0',
  'qshmProfileRawDigest',
    'sha256:2389902346fa1cef377bba7d8d575b03ef41e7f0614262b540df2e494f7fb882',
  'qshmProfileCanonicalDigest',
    'sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2',
  'qshmContractLockVersion', 'QSHM-CONTRACT-LOCK-1.0.0',
  'qshmContractLockRawDigest',
    'sha256:95eeb36ad905079eabf3f83addb40c335e0d9c862c4c582a5981e21b2379dc82',
  'qshmAuthorityRef', 'AUTH-2026-08-09-001',
  'qshmApprovalRef', 'AUTH-2026-08-09-001',
  'qshmEffectiveAt', '2026-08-09T11:24:55Z')
$$;

do $$
declare
    iq_policy jsonb;
    iq_projected integer;
begin
    select policy into strict iq_policy
      from ingestion_quality.iq_frozen_qmdp_policy
     where singleton
       and raw_digest = 'sha256:' || encode(sha256(raw_utf8), 'hex')
       and canonical_digest = 'sha256:' || encode(sha256(convert_to(
         ingestion_quality.iq_json_canonical(policy), 'UTF8')), 'hex');
    if iq_policy ->> 'profileVersion' <> 'QMDP-1.0.0'
       or jsonb_array_length(iq_policy -> 'commonMetrics') <> 12
       or jsonb_array_length(iq_policy -> 'sources') <> 17
       or (select count(*)
             from jsonb_array_elements(iq_policy -> 'sources') source(iq_source)
             cross join lateral jsonb_array_elements(
               iq_source -> 'sourceGates')) <> 30
       or exists (
         select 1
           from jsonb_array_elements(iq_policy -> 'sources') source(iq_source)
          where (select count(*)
                   from ingestion_quality.iq_qmdp_ordered_definitions(
                     iq_source ->> 'sourceId')) not between 11 and 14)
       or exists (
         select 1
           from jsonb_array_elements(iq_policy -> 'sources') source(iq_source)
          where (select count(*)
                   from ingestion_quality.iq_qmdp_ordered_definitions(
                     iq_source ->> 'sourceId')) <>
                (select count(distinct definition ->> 'formulaId')
                   from ingestion_quality.iq_qmdp_ordered_definitions(
                     iq_source ->> 'sourceId'))) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_QMDP_RUNTIME_PROJECTION_INVALID';
    end if;
    select count(*) into iq_projected
      from jsonb_array_elements(iq_policy -> 'sources') source(iq_source)
      cross join lateral ingestion_quality.iq_qmdp_ordered_definitions(
        iq_source ->> 'sourceId');
    if iq_projected <> 219 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_QMDP_RUNTIME_PROJECTION_INVALID';
    end if;
end
$$;


create table ingestion_quality.iq_data_batch (
    batch_id uuid primary key,
    source_id varchar(64) not null check (
        source_id ~ '^SRC-P[01]-[A-Z-]+-[0-9]{3}$'),
    business_key_utf8 bytea not null,
    business_key_digest char(64) not null check (
        business_key_digest ~ '^[0-9a-f]{64}$'),
    source_version bigint not null check (
        source_version between 1 and 9007199254740991),
    lineage_id uuid not null,
    supersedes_batch_id uuid references ingestion_quality.iq_data_batch(batch_id),
    correction_reason varchar(32) check (
        correction_reason is null
        or correction_reason in ('SOURCE_CORRECTION','LATE_ARRIVAL')),
    effective_at timestamptz not null,
    declared_manifest_digest char(71) not null check (
        declared_manifest_digest ~ '^sha256:[0-9a-f]{64}$'),
    status varchar(32) not null check (
        status in ('receiving','sealed','quality-passed','quality-failed','published')),
    aggregate_version bigint not null check (
        aggregate_version between 1 and 9007199254740991),
    normalized_fact_count bigint not null default 0,
    normalized_fact_schema_version varchar(128),
    normalized_fact_schema_digest char(71),
    record_count bigint,
    valid_record_count bigint,
    rejected_record_count bigint,
    observation_start_at timestamptz,
    observation_end_at timestamptz,
    cutoff_at timestamptz,
    business_timezone varchar(64),
    watermark_utf8 bytea,
    source_schema_version varchar(128),
    source_schema_digest char(71),
    data_catalog_version varchar(128),
    data_catalog_digest char(71),
    quality_gate_version varchar(128),
    quality_gate_digest char(71),
    qmdp_version varchar(128),
    qmdp_digest char(71),
    source_occurred_at timestamptz,
    scheduled_due_at timestamptz,
    lane_id varchar(128),
    sealed_contract_evidence jsonb,
    received_at timestamptz not null,
    sealed_at timestamptz,
    evaluated_at timestamptz,
    published_at timestamptz,
    trace_id char(32) not null check (
        trace_id ~ '^[0-9a-f]{32}$' and trace_id !~ '^0{32}$'),
    constraint iq_data_batch_identity_uk unique (
        source_id, business_key_digest, source_version),
    constraint iq_data_batch_successor_uk unique (supersedes_batch_id),
    check (substring(batch_id::text, 15, 1) = '7'
        and substring(batch_id::text, 20, 1) in ('8','9','a','b')),
    check (substring(lineage_id::text, 15, 1) = '7'
        and substring(lineage_id::text, 20, 1) in ('8','9','a','b')),
    check (supersedes_batch_id is null or (
        substring(supersedes_batch_id::text, 15, 1) = '7'
        and substring(supersedes_batch_id::text, 20, 1) in ('8','9','a','b'))),
    check (ingestion_quality.iq_utf8_scalar_count(business_key_utf8)
        between 1 and 1024),
    check (business_key_digest = encode(sha256(business_key_utf8), 'hex')),
    check ((supersedes_batch_id is null) = (correction_reason is null)),
    check (normalized_fact_count between 0 and 9007199254740991),
    check ((normalized_fact_count = 0
            and normalized_fact_schema_version is null
            and normalized_fact_schema_digest is null)
        or (normalized_fact_count > 0
            and normalized_fact_schema_version is not null
            and normalized_fact_schema_digest ~ '^sha256:[0-9a-f]{64}$')),
    check (record_count is null or record_count between 0 and 9007199254740991),
    check (valid_record_count is null or valid_record_count between 0 and 9007199254740991),
    check (rejected_record_count is null or rejected_record_count between 0 and 9007199254740991),
    check (record_count is null or record_count = valid_record_count + rejected_record_count),
    check (observation_start_at is null or observation_start_at < observation_end_at),
    check (business_timezone is null or business_timezone = 'Asia/Shanghai'),
    check (watermark_utf8 is null or
        ingestion_quality.iq_utf8_scalar_count(watermark_utf8) between 1 and 512),
    check (source_schema_digest is null or source_schema_digest ~ '^sha256:[0-9a-f]{64}$'),
    check (data_catalog_digest is null or data_catalog_digest ~ '^sha256:[0-9a-f]{64}$'),
    check (quality_gate_digest is null or quality_gate_digest ~ '^sha256:[0-9a-f]{64}$'),
    check (qmdp_digest is null or qmdp_digest ~ '^sha256:[0-9a-f]{64}$'),
    check (sealed_contract_evidence is null
        or (jsonb_typeof(sealed_contract_evidence) = 'object'
            and sealed_contract_evidence <> '{}'::jsonb)),
    check (
        (status = 'receiving' and aggregate_version = 1
            and record_count is null and valid_record_count is null
            and rejected_record_count is null and observation_start_at is null
            and observation_end_at is null and cutoff_at is null
            and business_timezone is null and watermark_utf8 is null
            and source_schema_version is null and source_schema_digest is null
            and data_catalog_version is null and data_catalog_digest is null
            and quality_gate_version is null and quality_gate_digest is null
            and qmdp_version is null and qmdp_digest is null
            and source_occurred_at is null and scheduled_due_at is null
            and lane_id is null and sealed_contract_evidence is null
            and sealed_at is null and evaluated_at is null and published_at is null)
        or
        (status = 'sealed' and aggregate_version = 2
            and record_count is not null and valid_record_count is not null
            and rejected_record_count is not null and observation_start_at is not null
            and observation_end_at is not null and cutoff_at is not null
            and business_timezone is not null and watermark_utf8 is not null
            and source_schema_version is not null and source_schema_digest is not null
            and data_catalog_version is not null and data_catalog_digest is not null
            and quality_gate_version is not null and quality_gate_digest is not null
            and qmdp_version is not null and qmdp_digest is not null
            and source_occurred_at is not null and scheduled_due_at is not null
            and lane_id is not null and sealed_contract_evidence is not null
            and sealed_at is not null and evaluated_at is null and published_at is null)
        or
        (status in ('quality-passed','quality-failed') and aggregate_version = 3
            and record_count is not null and valid_record_count is not null
            and rejected_record_count is not null and observation_start_at is not null
            and observation_end_at is not null and cutoff_at is not null
            and business_timezone is not null and watermark_utf8 is not null
            and source_schema_version is not null and source_schema_digest is not null
            and data_catalog_version is not null and data_catalog_digest is not null
            and quality_gate_version is not null and quality_gate_digest is not null
            and qmdp_version is not null and qmdp_digest is not null
            and source_occurred_at is not null and scheduled_due_at is not null
            and lane_id is not null and sealed_contract_evidence is not null
            and sealed_at is not null and evaluated_at is not null and published_at is null)
        or
        (status = 'published' and aggregate_version = 4
            and record_count is not null and valid_record_count is not null
            and rejected_record_count is not null and observation_start_at is not null
            and observation_end_at is not null and cutoff_at is not null
            and business_timezone is not null and watermark_utf8 is not null
            and source_schema_version is not null and source_schema_digest is not null
            and data_catalog_version is not null and data_catalog_digest is not null
            and quality_gate_version is not null and quality_gate_digest is not null
            and qmdp_version is not null and qmdp_digest is not null
            and source_occurred_at is not null and scheduled_due_at is not null
            and lane_id is not null and sealed_contract_evidence is not null
            and sealed_at is not null and evaluated_at is not null and published_at is not null)),
    check (sealed_at is null or sealed_at >= received_at),
    check (evaluated_at is null or evaluated_at >= sealed_at),
    check (published_at is null or published_at >= evaluated_at)
);

create unique index iq_data_batch_root_uk
    on ingestion_quality.iq_data_batch(source_id, business_key_digest)
    where supersedes_batch_id is null;
create unique index iq_data_batch_lineage_root_uk
    on ingestion_quality.iq_data_batch(lineage_id)
    where supersedes_batch_id is null;
create index iq_data_batch_lineage_idx
    on ingestion_quality.iq_data_batch(lineage_id, effective_at desc, batch_id);
create index iq_data_batch_status_idx
    on ingestion_quality.iq_data_batch(status, received_at, batch_id);

create table ingestion_quality.iq_normalized_fact (
    batch_id uuid not null references ingestion_quality.iq_data_batch(batch_id),
    record_id_utf8 bytea not null,
    record_id_digest char(64) not null check (record_id_digest ~ '^[0-9a-f]{64}$'),
    source_id varchar(64) not null check (source_id ~ '^SRC-P[01]-[A-Z-]+-[0-9]{3}$'),
    business_key_utf8 bytea not null,
    business_key_digest char(64) not null check (business_key_digest ~ '^[0-9a-f]{64}$'),
    source_version bigint not null check (source_version between 1 and 9007199254740991),
    source_schema_version varchar(128) not null,
    source_schema_digest char(71) not null check (
        source_schema_digest ~ '^sha256:[0-9a-f]{64}$'),
    lineage_id uuid not null,
    content_digest char(71) not null check (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    accepted_at timestamptz not null,
    primary key (batch_id, record_id_digest),
    check (ingestion_quality.iq_utf8_scalar_count(record_id_utf8) between 1 and 1024),
    check (record_id_digest = encode(sha256(record_id_utf8), 'hex')),
    check (ingestion_quality.iq_utf8_scalar_count(business_key_utf8) between 1 and 1024),
    check (business_key_digest = encode(sha256(business_key_utf8), 'hex')),
    check (substring(lineage_id::text, 15, 1) = '7'
        and substring(lineage_id::text, 20, 1) in ('8','9','a','b'))
);

create table ingestion_quality.iq_quality_snapshot (
    snapshot_id uuid primary key,
    snapshot_token_digest char(64) not null check (
        snapshot_token_digest ~ '^[0-9a-f]{64}$'),
    batch_id uuid not null unique references ingestion_quality.iq_data_batch(batch_id),
    domain_tag varchar(128) not null check (
        domain_tag = 'scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1'),
    hash_profile_version varchar(64) not null check (hash_profile_version = 'QSHM-1.0.0'),
    hash_profile_digest char(71) not null check (hash_profile_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_id varchar(64) not null check (source_id ~ '^SRC-P[01]-[A-Z-]+-[0-9]{3}$'),
    assessed_batch_status varchar(32) not null check (
        assessed_batch_status in ('quality-passed','quality-failed')),
    overall_result varchar(32) not null check (
        overall_result in ('quality-passed','quality-failed')),
    observation_start_at timestamptz not null,
    observation_end_at timestamptz not null,
    cutoff_at timestamptz not null,
    watermark_utf8 bytea not null,
    source_owner_ref varchar(256) not null,
    approval_ref varchar(64) not null check (approval_ref = 'AUTH-2026-08-08-001'),
    effective_at timestamptz not null,
    retention_schedule_version varchar(64) not null check (
        retention_schedule_version = 'RS-1.0.0'),
    qmdp_version varchar(64) not null check (qmdp_version = 'QMDP-1.0.0'),
    qmdp_digest char(71) not null check (qmdp_digest ~ '^sha256:[0-9a-f]{64}$'),
    quality_gate_version varchar(64) not null check (quality_gate_version = 'QG-1.0.0'),
    quality_gate_digest char(71) not null check (
        quality_gate_digest ~ '^sha256:[0-9a-f]{64}$'),
    canonicalization_profile varchar(64) not null check (
        canonicalization_profile = 'SCHOLARSENSE-CANONICAL-JSON-1.0.0'),
    manifest_digest char(71) not null check (manifest_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_schema_version varchar(128) not null,
    source_schema_digest char(71) not null check (
        source_schema_digest ~ '^sha256:[0-9a-f]{64}$'),
    lineage_id uuid not null,
    supersedes_snapshot_id uuid,
    evaluated_at timestamptz not null,
    trace_id char(32) not null check (
        trace_id ~ '^[0-9a-f]{32}$' and trace_id !~ '^0{32}$'),
    aggregate_version bigint not null check (
        aggregate_version between 1 and 9007199254740991),
    immutable_hash char(71) not null check (
        immutable_hash ~ '^sha256:[0-9a-f]{64}$'
        and immutable_hash <> 'sha256:0000000000000000000000000000000000000000000000000000000000000000'),
    retention_due_at timestamptz not null,
    legal_hold boolean not null default false,
    retention_scope_digest char(71) not null check (
        retention_scope_digest ~ '^sha256:[0-9a-f]{64}$'),
    check (substring(snapshot_id::text, 15, 1) = '7'
        and substring(snapshot_id::text, 20, 1) in ('8','9','a','b')),
    check (substring(lineage_id::text, 15, 1) = '7'
        and substring(lineage_id::text, 20, 1) in ('8','9','a','b')),
    check (supersedes_snapshot_id is null or (
        substring(supersedes_snapshot_id::text, 15, 1) = '7'
        and substring(supersedes_snapshot_id::text, 20, 1) in ('8','9','a','b'))),
    check (snapshot_id <> supersedes_snapshot_id),
    check (assessed_batch_status = overall_result),
    check (observation_start_at < observation_end_at),
    check (ingestion_quality.iq_utf8_scalar_count(watermark_utf8) between 1 and 512),
    check (effective_at <= evaluated_at),
    check (retention_due_at =
        ((evaluated_at at time zone 'UTC') + interval '2 years') at time zone 'UTC')
);

create function ingestion_quality.iq_bind_quality_snapshot_token_digest()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
    iq_expected char(64) := encode(
        sha256(convert_to(new.snapshot_id::text, 'UTF8')), 'hex');
begin
    if new.snapshot_token_digest is not null
       and new.snapshot_token_digest <> iq_expected then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SNAPSHOT_TOKEN_BINDING_INVALID';
    end if;
    new.snapshot_token_digest := iq_expected;
    return new;
end
$$;

revoke all on function ingestion_quality.iq_bind_quality_snapshot_token_digest()
    from public;
alter function ingestion_quality.iq_bind_quality_snapshot_token_digest()
    owner to scholarsense_ingestion_quality_batch_owner;

create trigger iq_quality_snapshot_token_digest_guard
before insert on ingestion_quality.iq_quality_snapshot
for each row execute function ingestion_quality.iq_bind_quality_snapshot_token_digest();

create index iq_quality_snapshot_source_idx
    on ingestion_quality.iq_quality_snapshot(
        source_id, evaluated_at desc, snapshot_id desc);
create index iq_quality_snapshot_assessed_page_idx
    on ingestion_quality.iq_quality_snapshot(evaluated_at desc, snapshot_id desc);
create index iq_quality_snapshot_result_page_idx
    on ingestion_quality.iq_quality_snapshot(
        overall_result, evaluated_at desc, snapshot_id desc);
create index iq_quality_snapshot_source_result_page_idx
    on ingestion_quality.iq_quality_snapshot(
        source_id, overall_result, evaluated_at desc, snapshot_id desc);
create index iq_quality_snapshot_retention_idx
    on ingestion_quality.iq_quality_snapshot(
        legal_hold, retention_due_at, snapshot_id);
create unique index iq_quality_snapshot_token_digest_idx
    on ingestion_quality.iq_quality_snapshot(snapshot_token_digest);

create table ingestion_quality.iq_quality_snapshot_audit_token_binding (
    snapshot_id uuid primary key references ingestion_quality.iq_quality_snapshot(snapshot_id)
        on delete cascade,
    object_search_token varchar(96) not null check (
        object_search_token ~ '^ost_v1_k[0-9]+_[0-9a-f]{64}$'),
    aggregate_search_token varchar(96) not null check (
        aggregate_search_token ~ '^agt_v1_k[0-9]+_[0-9a-f]{64}$'),
    key_version varchar(32) not null check (key_version ~ '^k[0-9]+$'),
    check (split_part(object_search_token, '_', 3) = key_version),
    check (split_part(aggregate_search_token, '_', 3) = key_version)
);

create table ingestion_quality.iq_quality_snapshot_metric (
    snapshot_id uuid not null references ingestion_quality.iq_quality_snapshot(snapshot_id)
        on delete cascade,
    metric_ordinal integer not null check (metric_ordinal >= 0),
    metric_id varchar(128) not null,
    formula_id varchar(256) not null check (formula_id like 'QMDP-1.0.0/%'),
    formula_version varchar(64) not null check (formula_version = '1.0.0'),
    result varchar(32) not null check (result in ('passed','failed','not-applicable')),
    applicable boolean not null,
    numerator bigint not null check (numerator between 0 and 9007199254740991),
    denominator bigint not null check (denominator between 0 and 9007199254740991),
    value_basis_points bigint check (
        value_basis_points is null
        or value_basis_points between 0 and 9007199254740991),
    unit varchar(32) not null check (
        unit in ('basis-point','count','millisecond','member-count')),
    operator varchar(8) not null check (operator in ('>=','<=','=')),
    threshold_numerator bigint not null check (
        threshold_numerator between 0 and 9007199254740991),
    threshold_denominator bigint not null check (
        threshold_denominator between 1 and 9007199254740991),
    boundary varchar(16) not null check (boundary in ('inclusive','exclusive')),
    reason_code varchar(128),
    primary key (snapshot_id, metric_ordinal),
    unique (snapshot_id, metric_id, formula_id),
    check (reason_code is null),
    check ((not applicable and result = 'not-applicable'
            and numerator = 0 and denominator = 0 and value_basis_points is null)
        or (applicable and result in ('passed','failed') and denominator > 0)),
    check (not applicable
        or (unit in ('basis-point','member-count')) = (value_basis_points is not null)),
    check (not applicable or unit not in ('count','millisecond') or denominator = 1),
    check (not applicable or unit <> 'member-count' or numerator <= denominator),
    check (not applicable or value_basis_points is null or value_basis_points =
        round(numerator::numeric * 10000 / denominator::numeric)::bigint),
    check (not applicable or ((result = 'passed') =
        case operator
          when '>=' then numerator::numeric * threshold_denominator::numeric
              >= denominator::numeric * threshold_numerator::numeric
          when '<=' then numerator::numeric * threshold_denominator::numeric
              <= denominator::numeric * threshold_numerator::numeric
          when '=' then numerator::numeric * threshold_denominator::numeric
              = denominator::numeric * threshold_numerator::numeric
        end))
);

create table ingestion_quality.iq_quality_snapshot_impact_scope (
    snapshot_id uuid not null references ingestion_quality.iq_quality_snapshot(snapshot_id)
        on delete cascade,
    scope_ordinal integer not null check (scope_ordinal >= 0),
    scope_code_utf8 bytea not null,
    primary key (snapshot_id, scope_ordinal),
    unique (snapshot_id, scope_code_utf8),
    check (ingestion_quality.iq_utf8_scalar_count(scope_code_utf8) between 1 and 64)
);

create table ingestion_quality.iq_batch_quality_impact_scope (
    batch_id uuid not null references ingestion_quality.iq_data_batch(batch_id),
    scope_code_utf8 bytea not null,
    recorded_at timestamptz not null,
    sealed_at timestamptz,
    primary key (batch_id, scope_code_utf8),
    check (ingestion_quality.iq_utf8_scalar_count(scope_code_utf8) between 1 and 64),
    check (sealed_at is null or sealed_at >= recorded_at)
);

create table ingestion_quality.iq_batch_idempotency (
    scope_digest char(64) primary key check (scope_digest ~ '^[0-9a-f]{64}$'),
    authenticated_actor varchar(256) not null,
    command_type varchar(32) not null check (
        command_type in ('receive','seal','evaluate','publish')),
    request_digest char(71) not null check (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    batch_id uuid not null references ingestion_quality.iq_data_batch(batch_id),
    status varchar(16) not null check (status in ('claimed','completed')),
    response_status varchar(32),
    response_aggregate_version bigint check (
        response_aggregate_version is null
        or response_aggregate_version between 1 and 9007199254740991),
    claimed_at timestamptz not null,
    completed_at timestamptz,
    expires_at timestamptz not null,
    check (expires_at = claimed_at + interval '90 days'),
    check ((status = 'completed') =
        (response_status is not null and response_aggregate_version is not null
            and completed_at is not null))
);

create index iq_batch_idempotency_expires_idx
    on ingestion_quality.iq_batch_idempotency(expires_at, scope_digest);

create or replace function ingestion_quality.iq_cleanup_expired(
    trusted_cutoff timestamptz)
returns bigint
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_database_now timestamptz := clock_timestamp();
    iq_effective_cutoff timestamptz;
    expired_catalogs uuid[] := array[]::uuid[];
    deleted_batch_idempotency bigint := 0;
    deleted_retention_authority bigint := 0;
    deleted_idempotency bigint := 0;
    deleted_outbox bigint := 0;
    deleted_audit bigint := 0;
    deleted_evidence bigint := 0;
    deleted_catalogs bigint := 0;
begin
    if trusted_cutoff is null
       or trusted_cutoff < iq_database_now - interval '5 minutes'
       or trusted_cutoff > iq_database_now + interval '5 minutes' then
        raise exception using
            errcode = 'invalid_parameter_value',
            message = 'INGESTION_QUALITY_RETENTION_TRUSTED_CUTOFF_INVALID';
    end if;
    -- A signed/trusted clock may be slightly ahead for availability, but it can
    -- never advance a destructive database eligibility boundary.
    iq_effective_cutoff := least(trusted_cutoff, iq_database_now);

    delete from ingestion_quality.iq_batch_idempotency idempotency
     where idempotency.expires_at <= iq_effective_cutoff;
    get diagnostics deleted_batch_idempotency = row_count;

    delete from ingestion_quality.iq_quality_snapshot_retention_authority_evidence authority
     where authority.consumed_at is null
       and authority.expires_at <= iq_effective_cutoff
       and not exists (
         select 1
           from ingestion_quality.iq_quality_snapshot_deletion_result result
          where result.authority_evidence_id = authority.authority_evidence_id);
    get diagnostics deleted_retention_authority = row_count;

    delete from ingestion_quality.iq_catalog_idempotency idempotency
     where idempotency.expires_at <= iq_effective_cutoff
       and not exists (
         select 1
           from ingestion_quality.iq_data_source_catalog held_catalog
          where held_catalog.catalog_id = idempotency.catalog_id
            and held_catalog.legal_hold);
    get diagnostics deleted_idempotency = row_count;

    delete from ingestion_quality.iq_local_audit_outbox outbox
     where exists (
       select 1
         from ingestion_quality.iq_local_audit_fact fact
        where fact.audit_id = outbox.audit_id
          and not fact.legal_hold
          and fact.expires_at <= iq_effective_cutoff
          and not exists (
            select 1
              from ingestion_quality.iq_data_source_catalog held_catalog
             where held_catalog.catalog_id = fact.catalog_id
               and held_catalog.legal_hold));
    get diagnostics deleted_outbox = row_count;

    delete from ingestion_quality.iq_local_audit_fact fact
     where not fact.legal_hold
       and fact.expires_at <= iq_effective_cutoff
       and not exists (
         select 1
           from ingestion_quality.iq_data_source_catalog held_catalog
          where held_catalog.catalog_id = fact.catalog_id
            and held_catalog.legal_hold);
    get diagnostics deleted_audit = row_count;

    delete from ingestion_quality.iq_catalog_evidence evidence
     where not evidence.legal_hold
       and evidence.expires_at <= iq_effective_cutoff
       and not exists (
         select 1
           from ingestion_quality.iq_data_source_catalog held_catalog
          where held_catalog.catalog_id = evidence.catalog_id
            and held_catalog.legal_hold);
    get diagnostics deleted_evidence = row_count;

    expired_catalogs := (
      select coalesce(array_agg(catalog.catalog_id), array[]::uuid[])
        from ingestion_quality.iq_data_source_catalog catalog
       where not catalog.legal_hold
         and catalog.expires_at <= iq_effective_cutoff
         and not exists (
           select 1
             from ingestion_quality.iq_catalog_current current_pointer
            where current_pointer.catalog_id = catalog.catalog_id)
         and not exists (
           select 1
             from ingestion_quality.iq_catalog_evidence evidence
            where evidence.catalog_id = catalog.catalog_id)
         and not exists (
           select 1
             from ingestion_quality.iq_local_audit_fact fact
            where fact.catalog_id = catalog.catalog_id)
         and not exists (
           select 1
             from ingestion_quality.iq_catalog_idempotency idempotency
            where idempotency.catalog_id = catalog.catalog_id));

    delete from ingestion_quality.iq_catalog_validation_attempt validation
     where validation.catalog_id = any(expired_catalogs);
    delete from ingestion_quality.iq_dependency_binding binding
     where binding.catalog_id = any(expired_catalogs);
    delete from ingestion_quality.iq_source_contract source_contract
     where source_contract.catalog_id = any(expired_catalogs);

    update ingestion_quality.iq_dependency_id_reservation reservation
       set first_catalog_id = (
         select binding.catalog_id
           from ingestion_quality.iq_dependency_binding binding
           join ingestion_quality.iq_data_source_catalog catalog
             on catalog.catalog_id = binding.catalog_id
          where binding.dependency_id = reservation.dependency_id
          order by catalog.created_at, catalog.catalog_id
          limit 1)
     where reservation.first_catalog_id = any(expired_catalogs);

    update ingestion_quality.iq_source_id_reservation reservation
       set first_catalog_id = (
         select source_contract.catalog_id
           from ingestion_quality.iq_source_contract source_contract
           join ingestion_quality.iq_data_source_catalog catalog
             on catalog.catalog_id = source_contract.catalog_id
          where source_contract.source_id = reservation.source_id
          order by catalog.created_at, catalog.catalog_id
          limit 1)
     where reservation.first_catalog_id = any(expired_catalogs);

    delete from ingestion_quality.iq_data_source_catalog catalog
     where catalog.catalog_id = any(expired_catalogs);
    get diagnostics deleted_catalogs = row_count;

    return deleted_batch_idempotency + deleted_retention_authority
        + deleted_idempotency + deleted_outbox
        + deleted_audit + deleted_evidence + deleted_catalogs;
end
$$;

create table ingestion_quality.iq_batch_quality_measurement (
    batch_id uuid not null references ingestion_quality.iq_data_batch(batch_id),
    formula_id varchar(256) not null check (formula_id like 'QMDP-1.0.0/%'),
    formula_ordinal integer not null check (formula_ordinal between 0 and 13),
    applicable boolean not null,
    evidence_digest char(71) not null check (
        evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    recorded_at timestamptz not null,
    sealed_at timestamptz,
    primary key (batch_id, formula_id),
    unique (batch_id, formula_ordinal),
    check (sealed_at is null or sealed_at >= recorded_at)
);

create table ingestion_quality.iq_batch_quality_operand (
    batch_id uuid not null,
    formula_id varchar(256) not null,
    operand_id varchar(128) not null,
    operand_value bigint not null check (
        operand_value between 0 and 9007199254740991),
    primary key (batch_id, formula_id, operand_id),
    foreign key (batch_id, formula_id)
        references ingestion_quality.iq_batch_quality_measurement(batch_id, formula_id)
        on delete cascade
);

create table ingestion_quality.iq_batch_quality_outbox (
    event_id uuid primary key,
    aggregate_id uuid not null,
    aggregate_version bigint not null check (
        aggregate_version between 1 and 9007199254740991),
    event_type varchar(160) not null check (
        event_type ~ '^scholarsense[.][a-z0-9.-]+[.]v1$'),
    schema_version varchar(64) not null,
    payload_utf8 bytea not null check (octet_length(payload_utf8) between 2 and 65536),
    payload_digest char(64) not null check (payload_digest ~ '^[0-9a-f]{64}$'),
    status varchar(16) not null default 'pending' check (
        status in ('pending','retrying','delivered','failed')),
    attempts bigint not null default 0 check (attempts between 0 and 8),
    available_at timestamptz not null,
    claimed_until timestamptz,
    delivered_at timestamptz,
    last_error_code varchar(128) check (
        last_error_code is null
        or last_error_code in (
          'BATCH_QUALITY_RELAY_UNAVAILABLE',
          'BATCH_QUALITY_PAYLOAD_INTEGRITY_INVALID',
          'BATCH_QUALITY_RELAY_ATTEMPTS_EXHAUSTED')),
    created_at timestamptz not null,
    unique (event_type, aggregate_id, aggregate_version),
    check (substring(event_id::text, 15, 1) = '7'
        and substring(event_id::text, 20, 1) in ('8','9','a','b')),
    check (payload_digest = encode(sha256(payload_utf8), 'hex'))
);

create index iq_batch_quality_outbox_due_idx
    on ingestion_quality.iq_batch_quality_outbox(status, available_at, event_id);

create table ingestion_quality.iq_quality_snapshot_retention_authority_evidence (
    authority_evidence_id uuid primary key,
    authority_ref varchar(512) not null check (btrim(authority_ref) <> ''),
    snapshot_id uuid not null,
    snapshot_immutable_hash char(71) not null check (
        snapshot_immutable_hash ~ '^sha256:[0-9a-f]{64}$'),
    scope_digest char(71) not null check (
        scope_digest ~ '^sha256:[0-9a-f]{64}$'),
    registry_version varchar(64) not null check (
        registry_version = 'QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0'),
    registry_digest char(71) not null check (
        registry_digest ~ '^sha256:[0-9a-f]{64}$'),
    members_digest char(71) not null check (
        members_digest ~ '^sha256:[0-9a-f]{64}$'),
    legal_hold_clear boolean not null,
    legal_hold_checked_scope_digest char(71) not null check (
        legal_hold_checked_scope_digest ~ '^sha256:[0-9a-f]{64}$'),
    legal_hold_checked_at timestamptz not null,
    consumer_attestations_payload_utf8 bytea not null check (
        octet_length(consumer_attestations_payload_utf8) between 2 and 65536),
    consumer_attestations_digest char(64) not null check (
        consumer_attestations_digest ~ '^[0-9a-f]{64}$'
        and consumer_attestations_digest =
            encode(sha256(consumer_attestations_payload_utf8), 'hex')),
    watermarks_checked_at timestamptz not null,
    evidence_copy_status varchar(32) not null check (
        evidence_copy_status in ('copied','not-required','missing','unavailable')),
    evidence_copy_digest char(71) not null check (
        evidence_copy_digest ~ '^sha256:[0-9a-f]{64}$'),
    trusted_observed_at timestamptz not null,
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    execution_id uuid,
    runtime_evidence_claim varchar(32) not null check (
        runtime_evidence_claim = 'production-verified'),
    verification_status varchar(32) not null check (
        verification_status = 'verified'),
    check (substring(authority_evidence_id::text, 15, 1) = '7'
        and substring(authority_evidence_id::text, 20, 1) in ('8','9','a','b')),
    check (substring(snapshot_id::text, 15, 1) = '7'
        and substring(snapshot_id::text, 20, 1) in ('8','9','a','b')),
    check (issued_at < expires_at),
    check (issued_at <= legal_hold_checked_at
        and legal_hold_checked_at <= trusted_observed_at),
    check (issued_at <= watermarks_checked_at
        and watermarks_checked_at <= trusted_observed_at),
    check ((consumed_at is null) = (execution_id is null)),
    check (consumed_at is null or (
        issued_at <= consumed_at and consumed_at <= trusted_observed_at)),
    check (execution_id is null or (
        substring(execution_id::text, 15, 1) = '7'
        and substring(execution_id::text, 20, 1) in ('8','9','a','b')))
);

create table ingestion_quality.iq_quality_snapshot_deletion_result (
    result_event_id uuid primary key,
    execution_id uuid not null,
    snapshot_id uuid not null,
    snapshot_immutable_hash char(71) not null check (
        snapshot_immutable_hash ~ '^sha256:[0-9a-f]{64}$'),
    scope_digest char(71) not null check (scope_digest ~ '^sha256:[0-9a-f]{64}$'),
    result varchar(16) not null check (result in ('blocked','completed','partial','failed')),
    blocker_code varchar(128),
    transaction_id uuid,
    transaction_evidence_digest char(71),
    authority_evidence_id uuid not null,
    aggregate_version bigint not null check (
        aggregate_version between 1 and 9007199254740991),
    supersedes_result_event_id uuid references
        ingestion_quality.iq_quality_snapshot_deletion_result(result_event_id),
    occurred_at timestamptz not null,
    payload_utf8 bytea not null check (octet_length(payload_utf8) between 2 and 65536),
    payload_digest char(64) not null check (payload_digest ~ '^[0-9a-f]{64}$'),
    unique (execution_id, aggregate_version),
    unique (authority_evidence_id),
    unique (supersedes_result_event_id),
    check (substring(result_event_id::text, 15, 1) = '7'
        and substring(result_event_id::text, 20, 1) in ('8','9','a','b')),
    check (substring(execution_id::text, 15, 1) = '7'
        and substring(execution_id::text, 20, 1) in ('8','9','a','b')),
    check ((result = 'blocked') = (blocker_code is not null)),
    check ((result = 'completed') =
        (transaction_id is not null and transaction_evidence_digest is not null)),
    check (transaction_evidence_digest is null
        or transaction_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    check (transaction_id is null or (
        substring(transaction_id::text, 15, 1) = '7'
        and substring(transaction_id::text, 20, 1) in ('8','9','a','b'))),
    check (transaction_id is null or transaction_id <> execution_id),
    check ((aggregate_version = 1) = (supersedes_result_event_id is null)),
    check (result_event_id <> supersedes_result_event_id),
    check (payload_digest = encode(sha256(payload_utf8), 'hex'))
);

alter table ingestion_quality.iq_local_audit_fact
    add column batch_id uuid,
    add column snapshot_id uuid,
    add column request_digest char(71),
    add constraint iq_local_audit_fact_batch_request_digest
        check (request_digest is null or request_digest ~ '^sha256:[0-9a-f]{64}$');

create view ingestion_quality.iq_published_normalized_fact as
select fact.batch_id,
       fact.record_id_utf8,
       fact.record_id_digest,
       fact.source_id,
       fact.business_key_utf8,
       fact.business_key_digest,
       fact.source_version,
       fact.source_schema_version,
       fact.source_schema_digest,
       fact.lineage_id,
       fact.content_digest,
       fact.accepted_at
  from ingestion_quality.iq_normalized_fact fact
  join ingestion_quality.iq_data_batch batch
    on batch.batch_id = fact.batch_id
 where batch.status = 'published';

create function ingestion_quality.iq_guard_data_batch_state()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
    iq_predecessor ingestion_quality.iq_data_batch%rowtype;
    iq_existing_lineage_row ingestion_quality.iq_data_batch%rowtype;
begin
    if tg_op = 'INSERT' then
        if new.status <> 'receiving' or new.aggregate_version <> 1 then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_INITIAL_BATCH_STATE_INVALID';
        end if;
        perform pg_catalog.pg_advisory_xact_lock(
            pg_catalog.hashtextextended(new.lineage_id::text, 1398096466));
        if new.supersedes_batch_id is null then
            select * into iq_existing_lineage_row
              from ingestion_quality.iq_data_batch
             where lineage_id = new.lineage_id
               and supersedes_batch_id is null
             for update;
            if found and (
               iq_existing_lineage_row.source_id <> new.source_id
               or iq_existing_lineage_row.business_key_utf8 <> new.business_key_utf8
               or iq_existing_lineage_row.business_key_digest <> new.business_key_digest
               or iq_existing_lineage_row.source_version <> new.source_version
               or iq_existing_lineage_row.declared_manifest_digest <>
                    new.declared_manifest_digest
               or iq_existing_lineage_row.effective_at <> new.effective_at) then
                raise exception using
                    errcode = 'check_violation',
                    message = 'INGESTION_QUALITY_BATCH_LINEAGE_ROOT_CONFLICT';
            end if;
        else
            if new.supersedes_batch_id = new.batch_id then
                raise exception using
                    errcode = 'check_violation',
                    message = 'INGESTION_QUALITY_BATCH_LINEAGE_SELF_REFERENCE';
            end if;
            select * into iq_predecessor
              from ingestion_quality.iq_data_batch
             where batch_id = new.supersedes_batch_id
             for update;
            if not found
               or iq_predecessor.source_id <> new.source_id
               or iq_predecessor.business_key_utf8 <> new.business_key_utf8
               or iq_predecessor.business_key_digest <> new.business_key_digest
               or iq_predecessor.lineage_id <> new.lineage_id
               or new.source_version <= iq_predecessor.source_version
               or new.effective_at < iq_predecessor.effective_at then
                raise exception using
                    errcode = 'check_violation',
                    message = 'INGESTION_QUALITY_BATCH_LINEAGE_BINDING_INVALID';
            end if;
            select * into iq_existing_lineage_row
              from ingestion_quality.iq_data_batch
             where supersedes_batch_id = new.supersedes_batch_id
             for update;
            if found and (
               iq_existing_lineage_row.source_id <> new.source_id
               or iq_existing_lineage_row.business_key_utf8 <> new.business_key_utf8
               or iq_existing_lineage_row.business_key_digest <> new.business_key_digest
               or iq_existing_lineage_row.source_version <> new.source_version
               or iq_existing_lineage_row.lineage_id <> new.lineage_id
               or iq_existing_lineage_row.correction_reason is distinct from
                    new.correction_reason
               or iq_existing_lineage_row.declared_manifest_digest <>
                    new.declared_manifest_digest
               or iq_existing_lineage_row.effective_at <> new.effective_at) then
                raise exception using
                    errcode = 'check_violation',
                    message = 'INGESTION_QUALITY_BATCH_LINEAGE_PREDECESSOR_NOT_HEAD';
            end if;
        end if;
        return new;
    end if;
    if new.batch_id <> old.batch_id
       or new.source_id <> old.source_id
       or new.business_key_utf8 <> old.business_key_utf8
       or new.business_key_digest <> old.business_key_digest
       or new.source_version <> old.source_version
       or new.lineage_id <> old.lineage_id
       or new.supersedes_batch_id is distinct from old.supersedes_batch_id
       or new.correction_reason is distinct from old.correction_reason
       or new.effective_at <> old.effective_at
       or new.declared_manifest_digest <> old.declared_manifest_digest
       or new.received_at <> old.received_at
       or new.trace_id <> old.trace_id then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BATCH_IDENTITY_IMMUTABLE';
    end if;
    if old.status = 'receiving' then
        if new.status = 'receiving' then
            if new.aggregate_version <> old.aggregate_version
               or not (new.normalized_fact_count = old.normalized_fact_count + 1)
               or new.normalized_fact_schema_version is null
               or new.normalized_fact_schema_digest is null
               or (old.normalized_fact_count = 0 and (
                    old.normalized_fact_schema_version is not null
                    or old.normalized_fact_schema_digest is not null))
               or (old.normalized_fact_count > 0 and (
                    new.normalized_fact_schema_version <>
                        old.normalized_fact_schema_version
                    or new.normalized_fact_schema_digest <>
                        old.normalized_fact_schema_digest))
               or (to_jsonb(new) - array[
                    'normalized_fact_count','normalized_fact_schema_version',
                    'normalized_fact_schema_digest']) is distinct from
                  (to_jsonb(old) - array[
                    'normalized_fact_count','normalized_fact_schema_version',
                    'normalized_fact_schema_digest']) then
                raise exception using
                    errcode = 'check_violation',
                    message = 'INGESTION_QUALITY_BATCH_FACT_ACCUMULATOR_INVALID';
            end if;
            return new;
        end if;
        if new.status <> 'sealed' or new.aggregate_version <> 2 then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_BATCH_TRANSITION_INVALID';
        end if;
    elsif old.status = 'sealed' then
        if new.status not in ('quality-passed','quality-failed')
           or new.aggregate_version <> 3
           or (to_jsonb(new) - array['status','aggregate_version','evaluated_at'])
              is distinct from
              (to_jsonb(old) - array['status','aggregate_version','evaluated_at']) then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_BATCH_TRANSITION_INVALID';
        end if;
    elsif old.status = 'quality-passed' then
        if new.status <> 'published' or new.aggregate_version <> 4
           or (to_jsonb(new) - array['status','aggregate_version','published_at'])
              is distinct from
              (to_jsonb(old) - array['status','aggregate_version','published_at']) then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_BATCH_TRANSITION_INVALID';
        end if;
    else
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BATCH_IMMUTABLE';
    end if;
    return new;
end
$$;

create trigger iq_data_batch_state_guard
before insert or update on ingestion_quality.iq_data_batch
for each row execute function ingestion_quality.iq_guard_data_batch_state();

create function ingestion_quality.iq_guard_normalized_fact_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
    iq_parent ingestion_quality.iq_data_batch%rowtype;
begin
    select * into iq_parent
      from ingestion_quality.iq_data_batch
     where batch_id = new.batch_id
     for update;
    if not found or iq_parent.status <> 'receiving' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_NORMALIZED_FACT_STATE_INVALID';
    end if;
    if iq_parent.source_id <> new.source_id
       or iq_parent.business_key_utf8 <> new.business_key_utf8
       or iq_parent.business_key_digest <> new.business_key_digest
       or iq_parent.source_version <> new.source_version
       or iq_parent.lineage_id <> new.lineage_id then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_NORMALIZED_FACT_BINDING_INVALID';
    end if;
    return new;
end
$$;

create trigger iq_normalized_fact_insert_guard
before insert on ingestion_quality.iq_normalized_fact
for each row execute function ingestion_quality.iq_guard_normalized_fact_insert();

create function ingestion_quality.iq_reject_immutable_batch_evidence()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    raise exception using
        errcode = 'check_violation',
        message = 'INGESTION_QUALITY_BATCH_EVIDENCE_IMMUTABLE';
end
$$;

create trigger iq_normalized_fact_immutable
before update or delete on ingestion_quality.iq_normalized_fact
for each row execute function ingestion_quality.iq_reject_immutable_batch_evidence();
create trigger iq_quality_snapshot_update_immutable
before update on ingestion_quality.iq_quality_snapshot
for each row execute function ingestion_quality.iq_reject_immutable_batch_evidence();
create trigger iq_quality_snapshot_metric_update_immutable
before update on ingestion_quality.iq_quality_snapshot_metric
for each row execute function ingestion_quality.iq_reject_immutable_batch_evidence();
create trigger iq_quality_snapshot_impact_update_immutable
before update on ingestion_quality.iq_quality_snapshot_impact_scope
for each row execute function ingestion_quality.iq_reject_immutable_batch_evidence();

create function ingestion_quality.iq_guard_retention_authority_evidence()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    if tg_op = 'DELETE'
       and old.consumed_at is null
       and old.execution_id is null
       and old.expires_at <= pg_catalog.clock_timestamp()
       and pg_catalog.pg_has_role(
            session_user,
            'scholarsense_ingestion_quality_retention_executor',
            'USAGE') then
        return old;
    end if;
    if tg_op = 'UPDATE'
       and old.consumed_at is null
       and old.execution_id is null
       and new.consumed_at is not null
       and new.execution_id is not null
       and (to_jsonb(new) - array['consumed_at','execution_id']) is not distinct from
           (to_jsonb(old) - array['consumed_at','execution_id']) then
        return new;
    end if;
    raise exception using
        errcode = 'check_violation',
        message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_IMMUTABLE';
end
$$;

create trigger iq_quality_snapshot_retention_authority_immutable
before update or delete
on ingestion_quality.iq_quality_snapshot_retention_authority_evidence
for each row execute function ingestion_quality.iq_guard_retention_authority_evidence();

create trigger iq_quality_snapshot_deletion_result_immutable
before update or delete on ingestion_quality.iq_quality_snapshot_deletion_result
for each row execute function ingestion_quality.iq_reject_immutable_batch_evidence();
create trigger iq_batch_quality_measurement_delete_immutable
before delete on ingestion_quality.iq_batch_quality_measurement
for each row execute function ingestion_quality.iq_reject_immutable_batch_evidence();
create trigger iq_batch_quality_operand_immutable
before update or delete on ingestion_quality.iq_batch_quality_operand
for each row execute function ingestion_quality.iq_reject_immutable_batch_evidence();

create function ingestion_quality.iq_guard_measurement_change()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
    iq_status varchar;
    iq_sealed_at timestamptz;
begin
    select status, sealed_at into iq_status, iq_sealed_at
      from ingestion_quality.iq_data_batch
     where batch_id = new.batch_id
     for update;
    if tg_op = 'INSERT' then
        if iq_status <> 'receiving' or new.sealed_at is not null then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_MEASUREMENT_STATE_INVALID';
        end if;
        return new;
    end if;
    if iq_status <> 'sealed'
       or old.sealed_at is not null
       or new.sealed_at <> iq_sealed_at
       or (to_jsonb(new) - 'sealed_at') is distinct from
          (to_jsonb(old) - 'sealed_at') then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_MEASUREMENT_IMMUTABLE';
    end if;
    return new;
end
$$;

create trigger iq_batch_quality_measurement_guard
before insert or update on ingestion_quality.iq_batch_quality_measurement
for each row execute function ingestion_quality.iq_guard_measurement_change();

create function ingestion_quality.iq_guard_batch_quality_impact_scope_change()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
    iq_status varchar;
    iq_sealed_at timestamptz;
begin
    select status, sealed_at into iq_status, iq_sealed_at
      from ingestion_quality.iq_data_batch
     where batch_id = new.batch_id
     for update;
    if tg_op = 'INSERT' then
        if iq_status <> 'receiving' or new.sealed_at is not null then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_IMPACT_SCOPE_STATE_INVALID';
        end if;
        return new;
    end if;
    if iq_status <> 'sealed'
       or old.sealed_at is not null
       or new.sealed_at <> iq_sealed_at
       or (to_jsonb(new) - 'sealed_at') is distinct from
          (to_jsonb(old) - 'sealed_at') then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_IMPACT_SCOPE_IMMUTABLE';
    end if;
    return new;
end
$$;

create trigger iq_batch_quality_impact_scope_guard
before insert or update on ingestion_quality.iq_batch_quality_impact_scope
for each row execute function
    ingestion_quality.iq_guard_batch_quality_impact_scope_change();
create trigger iq_batch_quality_impact_scope_delete_immutable
before delete on ingestion_quality.iq_batch_quality_impact_scope
for each row execute function ingestion_quality.iq_reject_immutable_batch_evidence();

create function ingestion_quality.iq_guard_snapshot_insert()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
    iq_parent ingestion_quality.iq_data_batch%rowtype;
begin
    select * into iq_parent
      from ingestion_quality.iq_data_batch
     where batch_id = new.batch_id
     for update;
    if not found or iq_parent.status <> 'sealed'
       or new.source_id <> iq_parent.source_id
       or new.assessed_batch_status not in ('quality-passed','quality-failed')
       or new.observation_start_at <> iq_parent.observation_start_at
       or new.observation_end_at <> iq_parent.observation_end_at
       or new.cutoff_at <> iq_parent.cutoff_at
       or new.watermark_utf8 <> iq_parent.watermark_utf8
       or new.manifest_digest <> iq_parent.declared_manifest_digest
       or new.source_schema_version <> iq_parent.source_schema_version
       or new.source_schema_digest <> iq_parent.source_schema_digest
       or new.lineage_id <> iq_parent.lineage_id
       or new.aggregate_version <> iq_parent.aggregate_version + 1 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SNAPSHOT_BATCH_BINDING_INVALID';
    end if;
    return new;
end
$$;

create trigger iq_quality_snapshot_insert_guard
before insert on ingestion_quality.iq_quality_snapshot
for each row execute function ingestion_quality.iq_guard_snapshot_insert();

create function ingestion_quality.iq_guard_batch_outbox_update()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
declare
    iq_transition_valid boolean := false;
begin
    if tg_op = 'UPDATE'
       and (to_jsonb(new) - array[
          'status','attempts','available_at','claimed_until','delivered_at','last_error_code'])
       is distinct from
       (to_jsonb(old) - array[
          'status','attempts','available_at','claimed_until','delivered_at','last_error_code']) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BATCH_OUTBOX_IMMUTABLE';
    end if;

    -- Every persisted row has one exact delivery shape. Error codes are a
    -- controlled, low-cardinality vocabulary; arbitrary provider text or
    -- sensitive record content may never enter the outbox control columns.
    if (
        (new.status = 'pending'
         and new.attempts = 0
         and new.claimed_until is null
         and new.delivered_at is null
         and new.last_error_code is null)
        or
        (new.status = 'retrying'
         and new.attempts >= 1
         and new.delivered_at is null
         and (
           (new.claimed_until is not null and new.last_error_code is null)
           or
           (new.claimed_until is null
            and new.last_error_code = 'BATCH_QUALITY_RELAY_UNAVAILABLE')))
        or
        (new.status = 'delivered'
         and new.attempts >= 1
         and new.claimed_until is null
         and new.delivered_at is not null
         and new.last_error_code is null)
        or
        (new.status = 'failed'
         and new.attempts >= 1
         and new.claimed_until is null
         and new.delivered_at is null
         and new.last_error_code in (
           'BATCH_QUALITY_RELAY_UNAVAILABLE',
           'BATCH_QUALITY_PAYLOAD_INTEGRITY_INVALID',
           'BATCH_QUALITY_RELAY_ATTEMPTS_EXHAUSTED'))
    ) is not true then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BATCH_OUTBOX_TRANSITION_INVALID';
    end if;

    if tg_op = 'INSERT' then
        if new.status <> 'pending' then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_BATCH_OUTBOX_TRANSITION_INVALID';
        end if;
        return new;
    end if;

    -- Delivered and failed are terminal even for apparent no-op updates.
    if old.status in ('delivered','failed') then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BATCH_OUTBOX_TRANSITION_INVALID';
    end if;

    -- Claim a due pending/released row, or reclaim an expired lease. A claim
    -- is the only reusable transition that advances attempts, exactly by one.
    if new.status = 'retrying'
       and old.attempts < 8
       and new.attempts = old.attempts + 1
       and new.claimed_until is not null
       and new.claimed_until > statement_timestamp()
       and new.delivered_at is null
       and new.last_error_code is null
       and new.available_at is not distinct from old.available_at
       and old.available_at <= statement_timestamp()
       and (
         old.status = 'pending'
         or
         (old.status = 'retrying'
          and (old.claimed_until is null
               or old.claimed_until <= statement_timestamp()))) then
        iq_transition_valid := true;

    -- Release an active claim for a controlled retry without consuming a
    -- second attempt. Retry scheduling may move forward, never backwards.
    elsif old.status = 'retrying'
       and old.attempts < 8
       and old.claimed_until is not null
       and old.claimed_until > statement_timestamp()
       and new.status = 'retrying'
       and new.attempts = old.attempts
       and new.claimed_until is null
       and new.delivered_at is null
       and new.last_error_code = 'BATCH_QUALITY_RELAY_UNAVAILABLE'
       and new.available_at >= statement_timestamp()
       and new.available_at <= statement_timestamp() + interval '1 hour' then
        iq_transition_valid := true;

    -- A successful delivery must complete a live claim and preserve the
    -- attempt/schedule coordinates that selected the event.
    elsif old.status = 'retrying'
       and old.claimed_until is not null
       and old.claimed_until > statement_timestamp()
       and new.status = 'delivered'
       and new.attempts = old.attempts
       and new.claimed_until is null
       and new.delivered_at is not null
       and new.delivered_at >= old.created_at
       and new.last_error_code is null
       and new.available_at is not distinct from old.available_at then
        iq_transition_valid := true;

    -- A claimed attempt may end terminally without another increment.
    elsif old.status = 'retrying'
       and old.claimed_until is not null
       and old.claimed_until > statement_timestamp()
       and new.status = 'failed'
       and new.attempts = old.attempts
       and new.claimed_until is null
       and new.delivered_at is null
       and new.last_error_code = 'BATCH_QUALITY_PAYLOAD_INTEGRITY_INVALID'
       and new.available_at >= old.available_at then
        iq_transition_valid := true;

    -- A claim at the retry ceiling cannot be recycled or reclaimed. Both an
    -- explicit release and a crashed, expired lease become terminal without
    -- manufacturing a ninth attempt.
    elsif old.status = 'retrying'
       and old.attempts = 8
       and old.claimed_until is not null
       and new.status = 'failed'
       and new.attempts = old.attempts
       and new.claimed_until is null
       and new.delivered_at is null
       and new.last_error_code = 'BATCH_QUALITY_RELAY_ATTEMPTS_EXHAUSTED'
       and new.available_at >= old.available_at then
        iq_transition_valid := true;

    -- Payload-integrity validation can terminally reject a due pending event
    -- in one atomic write; that validation itself consumes exactly one attempt.
    elsif old.status = 'pending'
       and old.available_at <= statement_timestamp()
       and new.status = 'failed'
       and new.attempts = old.attempts + 1
       and new.claimed_until is null
       and new.delivered_at is null
       and new.last_error_code = 'BATCH_QUALITY_PAYLOAD_INTEGRITY_INVALID'
       and new.available_at >= old.available_at then
        iq_transition_valid := true;
    end if;

    if not iq_transition_valid then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BATCH_OUTBOX_TRANSITION_INVALID';
    end if;
    return new;
end
$$;

create trigger iq_batch_quality_outbox_update_guard
before insert or update on ingestion_quality.iq_batch_quality_outbox
for each row execute function ingestion_quality.iq_guard_batch_outbox_update();

create function ingestion_quality.iq_require_exclusive_workload(requested_role name)
returns void
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_direct_membership_valid boolean;
    iq_reachable_memberships integer;
begin
    select count(*) = 1
           and coalesce(pg_catalog.bool_and(
                 granted_role.rolname = requested_role::text), false)
           and coalesce(pg_catalog.bool_and(membership.inherit_option), false)
           and not coalesce(pg_catalog.bool_or(membership.set_option), false)
           and not coalesce(pg_catalog.bool_or(membership.admin_option), false)
      into iq_direct_membership_valid
      from pg_catalog.pg_auth_members membership
      join pg_catalog.pg_roles member_role
        on member_role.oid = membership.member
      join pg_catalog.pg_roles granted_role
        on granted_role.oid = membership.roleid
     where member_role.rolname = session_user;

    with recursive reachable(roleid) as (
      select membership.roleid
        from pg_catalog.pg_auth_members membership
        join pg_catalog.pg_roles member_role
          on member_role.oid = membership.member
       where member_role.rolname = session_user
      union
      select membership.roleid
        from pg_catalog.pg_auth_members membership
        join reachable
          on membership.member = reachable.roleid
    )
    select count(*) into iq_reachable_memberships from reachable;

    if requested_role::text not in (
         'scholarsense_ingestion_quality_online',
         'scholarsense_ingestion_quality_quality_worker',
         'scholarsense_ingestion_quality_relay',
         'scholarsense_ingestion_quality_retention_executor',
         'scholarsense_ingestion_quality_consumer_registry_authority')
       or not iq_direct_membership_valid
       or iq_reachable_memberships <> 1
       or not pg_catalog.pg_has_role(session_user, requested_role, 'USAGE')
       or pg_catalog.pg_has_role(
            session_user, 'scholarsense_ingestion_quality_batch_owner', 'MEMBER')
       or pg_catalog.pg_has_role(
            session_user, 'scholarsense_ingestion_quality_batch_owner', 'USAGE')
       or pg_catalog.pg_has_role(
            session_user, 'scholarsense_ingestion_quality_batch_owner', 'SET') then
        raise exception using
            errcode = 'insufficient_privilege',
            message = 'INGESTION_QUALITY_WORKLOAD_ROLE_MISMATCH';
    end if;
end
$$;

-- The business relay never receives raw UPDATE authority. These four closed
-- routines own the database clock, lease, retry delay, error vocabulary, and
-- attempt counter. The attempt supplied by a finalizer is a fencing token:
-- an expired claimant can never complete a newer claim.
create function ingestion_quality.iq_claim_next_batch_quality_outbox()
returns table(event_id uuid, attempts bigint, claimed_until timestamptz)
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_now timestamptz := statement_timestamp();
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_relay');

    -- A crashed eighth attempt is terminalized without creating attempt nine.
    -- SKIP LOCKED keeps one relay from waiting behind another relay's row lock.
    with iq_exhausted as (
      select candidate.event_id
        from ingestion_quality.iq_batch_quality_outbox candidate
       where candidate.status = 'retrying'
         and candidate.attempts = 8
         and candidate.claimed_until is not null
         and candidate.claimed_until <= iq_now
       order by candidate.claimed_until, candidate.event_id
       limit 1
       for update skip locked
    )
    update ingestion_quality.iq_batch_quality_outbox exhausted
       set status = 'failed',
           available_at = greatest(exhausted.available_at, iq_now),
           claimed_until = null,
           delivered_at = null,
           last_error_code = 'BATCH_QUALITY_RELAY_ATTEMPTS_EXHAUSTED'
      from iq_exhausted
     where exhausted.event_id = iq_exhausted.event_id;

    return query
    with iq_candidate as (
      select candidate.event_id
        from ingestion_quality.iq_batch_quality_outbox candidate
       where candidate.attempts < 8
         and candidate.available_at <= iq_now
         and (
           candidate.status = 'pending'
           or
           (candidate.status = 'retrying'
            and (candidate.claimed_until is null
                 or candidate.claimed_until <= iq_now)))
       order by candidate.available_at, candidate.event_id
       limit 1
       for update skip locked
    )
    update ingestion_quality.iq_batch_quality_outbox claimed
       set status = 'retrying',
           attempts = claimed.attempts + 1,
           claimed_until = iq_now + interval '5 minutes',
           delivered_at = null,
           last_error_code = null
      from iq_candidate
     where claimed.event_id = iq_candidate.event_id
    returning claimed.event_id, claimed.attempts, claimed.claimed_until;
end
$$;

create function ingestion_quality.iq_release_batch_quality_outbox(
    requested_event_id uuid,
    requested_expected_attempt bigint)
returns boolean
language plpgsql
volatile
strict
security definer
set search_path = pg_catalog
as $$
declare
    iq_now timestamptz := statement_timestamp();
    iq_changed integer;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_relay');

    if requested_expected_attempt = 8 then
        update ingestion_quality.iq_batch_quality_outbox claimed
           set status = 'failed',
               available_at = greatest(claimed.available_at, iq_now),
               claimed_until = null,
               delivered_at = null,
               last_error_code = 'BATCH_QUALITY_RELAY_ATTEMPTS_EXHAUSTED'
         where claimed.event_id = requested_event_id
           and claimed.status = 'retrying'
           and claimed.attempts = requested_expected_attempt
           and claimed.claimed_until is not null
           and claimed.claimed_until > iq_now;
    elsif requested_expected_attempt between 1 and 7 then
        update ingestion_quality.iq_batch_quality_outbox claimed
           set status = 'retrying',
               -- Linear database-owned backoff is deliberately simple and
               -- remains far below the one-hour production ceiling.
               available_at = iq_now
                   + interval '1 minute'
                     * requested_expected_attempt::double precision,
               claimed_until = null,
               delivered_at = null,
               last_error_code = 'BATCH_QUALITY_RELAY_UNAVAILABLE'
         where claimed.event_id = requested_event_id
           and claimed.status = 'retrying'
           and claimed.attempts = requested_expected_attempt
           and claimed.claimed_until is not null
           and claimed.claimed_until > iq_now;
    else
        return false;
    end if;

    get diagnostics iq_changed = row_count;
    return iq_changed = 1;
end
$$;

create function ingestion_quality.iq_deliver_batch_quality_outbox(
    requested_event_id uuid,
    requested_expected_attempt bigint)
returns boolean
language plpgsql
volatile
strict
security definer
set search_path = pg_catalog
as $$
declare
    iq_now timestamptz := statement_timestamp();
    iq_changed integer;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_relay');
    update ingestion_quality.iq_batch_quality_outbox claimed
       set status = 'delivered',
           claimed_until = null,
           delivered_at = iq_now,
           last_error_code = null
     where claimed.event_id = requested_event_id
       and claimed.status = 'retrying'
       and claimed.attempts = requested_expected_attempt
       and claimed.claimed_until is not null
       and claimed.claimed_until > iq_now;
    get diagnostics iq_changed = row_count;
    return iq_changed = 1;
end
$$;

create function ingestion_quality.iq_fail_batch_quality_outbox(
    requested_event_id uuid,
    requested_expected_attempt bigint)
returns boolean
language plpgsql
volatile
strict
security definer
set search_path = pg_catalog
as $$
declare
    iq_now timestamptz := statement_timestamp();
    iq_changed integer;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_relay');
    update ingestion_quality.iq_batch_quality_outbox claimed
       set status = 'failed',
           available_at = greatest(claimed.available_at, iq_now),
           claimed_until = null,
           delivered_at = null,
           last_error_code = 'BATCH_QUALITY_PAYLOAD_INTEGRITY_INVALID'
     where claimed.event_id = requested_event_id
       and claimed.status = 'retrying'
       and claimed.attempts = requested_expected_attempt
       and claimed.claimed_until is not null
       and claimed.claimed_until > iq_now;
    get diagnostics iq_changed = row_count;
    return iq_changed = 1;
end
$$;

create function ingestion_quality.iq_claim_batch_command(
    requested_scope_digest char(64),
    requested_actor varchar,
    requested_command_type varchar,
    requested_request_digest char(71),
    requested_batch_id uuid,
    requested_at timestamptz)
returns varchar
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_existing ingestion_quality.iq_batch_idempotency%rowtype;
    iq_processing_at timestamptz;
begin
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(requested_scope_digest, 1227901253));
    iq_processing_at := pg_catalog.clock_timestamp();
    insert into ingestion_quality.iq_batch_idempotency
      (scope_digest, authenticated_actor, command_type, request_digest,
       batch_id, status, claimed_at, expires_at)
    values
      (requested_scope_digest, requested_actor, requested_command_type,
       requested_request_digest, requested_batch_id, 'claimed', iq_processing_at,
       iq_processing_at + interval '90 days')
    on conflict (scope_digest) do nothing;
    select * into iq_existing
      from ingestion_quality.iq_batch_idempotency
     where scope_digest = requested_scope_digest
     for update;
    if iq_existing.expires_at <= iq_processing_at then
        update ingestion_quality.iq_batch_idempotency
           set authenticated_actor = requested_actor,
               command_type = requested_command_type,
               request_digest = requested_request_digest,
               batch_id = requested_batch_id,
               status = 'claimed',
               response_status = null,
               response_aggregate_version = null,
               claimed_at = iq_processing_at,
               completed_at = null,
               expires_at = iq_processing_at + interval '90 days'
         where scope_digest = requested_scope_digest;
        return 'claimed';
    end if;
    if iq_existing.request_digest <> requested_request_digest
       or iq_existing.authenticated_actor <> requested_actor
       or iq_existing.command_type <> requested_command_type
       or iq_existing.batch_id <> requested_batch_id then
        raise exception using
            errcode = 'unique_violation',
            message = 'INGESTION_QUALITY_IDEMPOTENCY_MISMATCH';
    end if;
    return iq_existing.status;
end
$$;

create function ingestion_quality.iq_inspect_batch_command_precedence(
    requested_scope_digest char(64),
    requested_command_type varchar,
    requested_request_digest char(71))
returns table(
    disposition varchar,
    batch_id uuid,
    response_status varchar,
    response_aggregate_version bigint,
    completed_at timestamptz,
    expires_at timestamptz)
language plpgsql
volatile
security definer
set search_path = pg_catalog
as $$
declare
    iq_existing ingestion_quality.iq_batch_idempotency%rowtype;
    iq_processing_at timestamptz;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_quality_worker');
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(requested_scope_digest, 1227901253));
    iq_processing_at := pg_catalog.clock_timestamp();
    select idempotency.* into iq_existing
      from ingestion_quality.iq_batch_idempotency idempotency
     where idempotency.scope_digest = requested_scope_digest;
    if not found or iq_existing.expires_at <= iq_processing_at then
        return query select
            'fresh'::varchar, null::uuid, null::varchar, null::bigint,
            null::timestamptz, null::timestamptz;
        return;
    end if;
    if iq_existing.authenticated_actor <> session_user::text
       or iq_existing.command_type <> requested_command_type
       or iq_existing.request_digest <> requested_request_digest then
        return query select
            'mismatch'::varchar, null::uuid, null::varchar, null::bigint,
            null::timestamptz, null::timestamptz;
        return;
    end if;
    if iq_existing.status = 'claimed' then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE';
    end if;
    if iq_existing.status <> 'completed' then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE';
    end if;
    return query select
        'replay'::varchar, iq_existing.batch_id,
        iq_existing.response_status::varchar,
        iq_existing.response_aggregate_version,
        iq_existing.completed_at, iq_existing.expires_at;
    return;
end
$$;

create function ingestion_quality.iq_complete_batch_command(
    requested_scope_digest char(64),
    requested_status varchar,
    requested_aggregate_version bigint,
    requested_at timestamptz)
returns void
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_processing_at timestamptz;
begin
    iq_processing_at := pg_catalog.clock_timestamp();
    update ingestion_quality.iq_batch_idempotency
       set status = 'completed', response_status = requested_status,
           response_aggregate_version = requested_aggregate_version,
           completed_at = iq_processing_at
     where scope_digest = requested_scope_digest and status = 'claimed';
    if not found then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_IDEMPOTENCY_COMPLETE_CONFLICT';
    end if;
end
$$;

create function ingestion_quality.iq_validate_batch_audit_payload(
    requested_audit_id uuid,
    requested_event_id uuid,
    requested_batch_id uuid,
    requested_action varchar,
    requested_result varchar,
    requested_aggregate_version bigint,
    requested_trace_id char(32),
    requested_request_digest char(71),
    requested_occurred_at timestamptz,
    requested_payload jsonb,
    requested_payload_digest char(64))
returns void
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_fact jsonb;
    iq_authorization jsonb;
    iq_time_profile jsonb;
    iq_actor_token text := 'ast_v1_k1_' || encode(
        sha256(convert_to(session_user::text, 'UTF8')), 'hex');
    iq_object_token text := 'ost_v1_k1_' || encode(
        sha256(convert_to(requested_batch_id::text, 'UTF8')), 'hex');
    iq_aggregate_token text := 'agt_v1_k1_' || encode(
        sha256(convert_to(requested_batch_id::text, 'UTF8')), 'hex');
    iq_time_valid boolean := false;
    iq_canonical_digest text;
begin
    if not ingestion_quality.iq_json_exact_object(requested_payload, array[
         'eventId','auditId','eventType','schemaVersion','producer','createdAt','fact'])
       or jsonb_typeof(requested_payload -> 'eventId') <> 'string'
       or jsonb_typeof(requested_payload -> 'auditId') <> 'string'
       or jsonb_typeof(requested_payload -> 'eventType') <> 'string'
       or jsonb_typeof(requested_payload -> 'schemaVersion') <> 'string'
       or jsonb_typeof(requested_payload -> 'producer') <> 'string'
       or jsonb_typeof(requested_payload -> 'createdAt') <> 'string'
       or requested_payload ->> 'eventId' <> requested_event_id::text
       or requested_payload ->> 'auditId' <> requested_audit_id::text
       or requested_payload ->> 'eventType' <>
          'ingestion-quality.local-audit-fact.recorded.v1'
       or requested_payload ->> 'schemaVersion' <> 'LOCAL-AUDIT-OUTBOX-1.0.0'
       or requested_payload ->> 'producer' <> 'ingestion-quality'
       or substring(requested_event_id::text, 15, 1) <> '7'
       or substring(requested_event_id::text, 20, 1) not in ('8','9','a','b')
       or substring(requested_audit_id::text, 15, 1) <> '7'
       or substring(requested_audit_id::text, 20, 1) not in ('8','9','a','b') then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_AUDIT_PAYLOAD_INVALID';
    end if;
    iq_fact := requested_payload -> 'fact';
    if not ingestion_quality.iq_json_exact_object(iq_fact, array[
         'auditId','schemaVersion','producerModule','actorType','actorSearchToken',
         'roleIds','authorizationContext','action','objectType','objectSearchToken',
         'outcome','reasonCode','purpose','projectionScope','occurredAt','recordedAt',
         'timeSourceProfile','sourceIpSearchToken','tokenizationProfileVersion',
         'keyVersion','traceId','aggregateType','aggregateIdSearchToken',
         'aggregateVersion','idempotencyKeyDigest','policyVersions',
         'retentionScheduleVersion'])
       or iq_fact ->> 'auditId' <> requested_audit_id::text
       or iq_fact ->> 'schemaVersion' <> 'LOCAL-AUDIT-FACT-1.0.0'
       or iq_fact ->> 'producerModule' <> 'ingestion-quality'
       or iq_fact ->> 'actorType' <> 'SERVICE'
       or iq_fact ->> 'actorSearchToken' <> iq_actor_token
       or iq_fact -> 'roleIds' <> '["QUALITY_WORKER"]'::jsonb
       or iq_fact ->> 'action' <> requested_action
       or iq_fact ->> 'objectType' <> 'data-batch'
       or iq_fact ->> 'objectSearchToken' <> iq_object_token
       or iq_fact ->> 'outcome' <> requested_result
       or requested_result not in ('accepted','rejected')
       or (requested_result = 'accepted' and iq_fact ->> 'reasonCode' <>
            'INGESTION_QUALITY_COMMAND_ACCEPTED')
       or (requested_result = 'rejected' and iq_fact ->> 'reasonCode' <>
            'INGESTION_QUALITY_COMMAND_REJECTED')
       or iq_fact ->> 'purpose' <> 'DATA_QUALITY'
       or iq_fact ->> 'projectionScope' <> 'QUALITY_WORKLOAD'
       or iq_fact -> 'sourceIpSearchToken' <> 'null'::jsonb
       or iq_fact ->> 'tokenizationProfileVersion' <> 'AUDIT-TOKENIZATION-1.0.0'
       or iq_fact ->> 'keyVersion' !~ '^k[0-9]+$'
       or iq_fact ->> 'traceId' <> requested_trace_id
       or iq_fact ->> 'aggregateType' <> 'data-batch'
       or iq_fact ->> 'aggregateIdSearchToken' <> iq_aggregate_token
       or jsonb_typeof(iq_fact -> 'aggregateVersion') <> 'number'
       or (iq_fact ->> 'aggregateVersion') !~ '^[1-9][0-9]*$'
       or (iq_fact ->> 'aggregateVersion')::numeric <> requested_aggregate_version
       or iq_fact ->> 'idempotencyKeyDigest' <>
          substring(requested_request_digest::text from 8)
       or iq_fact -> 'policyVersions' <> jsonb_build_object(
          'workloadAuthorization',
          'INGESTION-QUALITY-WORKLOAD-AUTHORIZATION-1.0.0')
       or iq_fact ->> 'retentionScheduleVersion' <> 'RS-1.0.0' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_AUDIT_PAYLOAD_INVALID';
    end if;
    iq_authorization := iq_fact -> 'authorizationContext';
    if not ingestion_quality.iq_json_exact_object(iq_authorization, array[
         'decision','policyVersion','scopeCodes','grantSearchTokens','notApplicableReason'])
       or iq_authorization ->> 'decision' <> 'allow'
       or iq_authorization ->> 'policyVersion' <>
          'INGESTION-QUALITY-WORKLOAD-AUTHORIZATION-1.0.0'
       or iq_authorization -> 'scopeCodes' <> '["QUALITY_WORKLOAD"]'::jsonb
       or iq_authorization -> 'grantSearchTokens' <> '[]'::jsonb
       or iq_authorization -> 'notApplicableReason' <> 'null'::jsonb then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_AUDIT_PAYLOAD_INVALID';
    end if;
    iq_time_profile := iq_fact -> 'timeSourceProfile';
    if not ingestion_quality.iq_json_exact_object(iq_time_profile, array[
         'sourceId','profileVersion','offsetMs','observedAt','freshUntil','evidenceRef'])
       or jsonb_typeof(iq_fact -> 'occurredAt') <> 'string'
       or jsonb_typeof(iq_fact -> 'recordedAt') <> 'string'
       or jsonb_typeof(iq_time_profile -> 'sourceId') <> 'string'
       or iq_time_profile ->> 'sourceId' !~ '^[a-z][a-z0-9.-]{2,63}$'
       or iq_time_profile ->> 'profileVersion' <> 'AUDIT-CLOCK-BINDING-1.0.0'
       or jsonb_typeof(iq_time_profile -> 'offsetMs') <> 'number'
       or (iq_time_profile ->> 'offsetMs') !~ '^-?(0|[1-9][0-9]*)$'
       or abs((iq_time_profile ->> 'offsetMs')::numeric) > 9007199254740991
       or jsonb_typeof(iq_time_profile -> 'observedAt') <> 'string'
       or jsonb_typeof(iq_time_profile -> 'freshUntil') <> 'string'
       or iq_time_profile ->> 'evidenceRef' !~
          '^evidence://signed/[A-Za-z0-9._/-]{3,240}$' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_AUDIT_PAYLOAD_INVALID';
    end if;
    begin
        iq_time_valid := (requested_payload ->> 'createdAt')::timestamptz =
                requested_occurred_at
            and (iq_fact ->> 'occurredAt')::timestamptz = requested_occurred_at
            and (iq_fact ->> 'recordedAt')::timestamptz = requested_occurred_at
            and (iq_time_profile ->> 'observedAt')::timestamptz <= requested_occurred_at
            and (iq_time_profile ->> 'freshUntil')::timestamptz > requested_occurred_at;
    exception when others then
        iq_time_valid := false;
    end;
    if not iq_time_valid then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_AUDIT_PAYLOAD_INVALID';
    end if;
    begin
        iq_canonical_digest := encode(sha256(convert_to(
            ingestion_quality.iq_json_canonical(requested_payload), 'UTF8')), 'hex');
    exception when others then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_AUDIT_PAYLOAD_INVALID';
    end;
    if iq_canonical_digest <> requested_payload_digest then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_AUDIT_PAYLOAD_DIGEST_MISMATCH';
    end if;
end
$$;

create function ingestion_quality.iq_append_batch_audit(
    requested_audit_id uuid,
    requested_event_id uuid,
    requested_batch_id uuid,
    requested_action varchar,
    requested_result varchar,
    requested_aggregate_version bigint,
    requested_trace_id char(32),
    requested_request_digest char(71),
    requested_occurred_at timestamptz,
    requested_payload jsonb,
    requested_payload_digest char(64))
returns void
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_actor_token varchar(128);
begin
    iq_actor_token := 'ast_v1_k1_' || encode(
        sha256(convert_to(session_user::text, 'UTF8')), 'hex');
    perform ingestion_quality.iq_validate_batch_audit_payload(
        requested_audit_id, requested_event_id, requested_batch_id,
        requested_action, requested_result, requested_aggregate_version,
        requested_trace_id, requested_request_digest, requested_occurred_at,
        requested_payload, requested_payload_digest);
    insert into ingestion_quality.iq_local_audit_fact
      (audit_id, actor_search_token, action, result, batch_id,
       aggregate_version, trace_id, occurred_at, authorization_context,
       request_digest, expires_at)
    values
      (requested_audit_id, iq_actor_token, requested_action, requested_result,
       requested_batch_id, requested_aggregate_version, requested_trace_id,
       requested_occurred_at, requested_payload #> '{fact,authorizationContext}',
       requested_request_digest,
       ((requested_occurred_at at time zone 'UTC') + interval '3 years') at time zone 'UTC');
    insert into ingestion_quality.iq_local_audit_outbox
      (event_id, audit_id, event_type, schema_version, producer,
       payload, payload_digest, available_at, created_at)
    values
      (requested_event_id, requested_audit_id,
       'ingestion-quality.local-audit-fact.recorded.v1',
       'LOCAL-AUDIT-OUTBOX-1.0.0', 'ingestion-quality', requested_payload,
       requested_payload_digest, requested_occurred_at, requested_occurred_at);
end
$$;

create function ingestion_quality.iq_receive_data_batch(
    requested_command_id uuid,
    requested_batch_id uuid,
    requested_source_id varchar,
    requested_business_key_utf8 bytea,
    requested_source_version bigint,
    requested_lineage_id uuid,
    requested_supersedes_batch_id uuid,
    requested_correction_reason varchar,
    requested_effective_at timestamptz,
    requested_manifest_digest char(71),
    requested_received_at timestamptz,
    requested_trace_id char(32),
    requested_scope_digest char(64),
    requested_request_digest char(71),
    requested_audit_payload jsonb,
    requested_audit_payload_digest char(64))
returns table(batch_id uuid, disposition varchar)
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_batch ingestion_quality.iq_data_batch%rowtype;
    iq_preflight_claim ingestion_quality.iq_batch_idempotency%rowtype;
    iq_claim varchar;
    iq_business_key_digest char(64);
    iq_processing_at timestamptz;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_quality_worker');
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(requested_scope_digest, 1227901253));
    iq_processing_at := pg_catalog.clock_timestamp();
    select * into iq_preflight_claim
      from ingestion_quality.iq_batch_idempotency
     where scope_digest = requested_scope_digest
     for update;
    if found
       and iq_preflight_claim.expires_at > iq_processing_at
       and (iq_preflight_claim.request_digest <> requested_request_digest
            or iq_preflight_claim.authenticated_actor <> session_user::text
            or iq_preflight_claim.command_type <> 'receive') then
        raise exception using
            errcode = 'unique_violation',
            message = 'INGESTION_QUALITY_IDEMPOTENCY_MISMATCH';
    end if;
    iq_business_key_digest := encode(sha256(requested_business_key_utf8), 'hex');
    insert into ingestion_quality.iq_data_batch
      (batch_id, source_id, business_key_utf8, business_key_digest,
       source_version, lineage_id, supersedes_batch_id, correction_reason,
       effective_at, declared_manifest_digest, status, aggregate_version,
       received_at, trace_id)
    values
      (requested_batch_id, requested_source_id, requested_business_key_utf8,
       iq_business_key_digest, requested_source_version, requested_lineage_id,
       requested_supersedes_batch_id, requested_correction_reason,
       requested_effective_at, requested_manifest_digest, 'receiving', 1,
       requested_received_at, requested_trace_id)
    on conflict (source_id, business_key_digest, source_version) do nothing;
    select * into iq_batch
      from ingestion_quality.iq_data_batch
     where source_id = requested_source_id
       and business_key_digest = iq_business_key_digest
       and source_version = requested_source_version
     for update;
    if not found
       or iq_batch.business_key_utf8 <> requested_business_key_utf8
       or iq_batch.declared_manifest_digest <> requested_manifest_digest
       or iq_batch.lineage_id <> requested_lineage_id
       or iq_batch.supersedes_batch_id is distinct from requested_supersedes_batch_id
       or iq_batch.correction_reason is distinct from requested_correction_reason
       or iq_batch.effective_at <> requested_effective_at then
        raise exception using
            errcode = 'unique_violation',
            message = 'INGESTION_QUALITY_BATCH_IDENTITY_CONFLICT';
    end if;
    iq_claim := ingestion_quality.iq_claim_batch_command(
        requested_scope_digest, session_user::text, 'receive',
        requested_request_digest, iq_batch.batch_id, iq_processing_at);
    if iq_claim = 'completed' then
        return query select iq_batch.batch_id, 'replay'::varchar;
        return;
    end if;
    perform ingestion_quality.iq_append_batch_audit(
        requested_command_id, requested_command_id, iq_batch.batch_id,
        'data-batch.receive', 'accepted',
        iq_batch.aggregate_version, requested_trace_id, requested_request_digest,
        (requested_audit_payload #>> '{fact,occurredAt}')::timestamptz,
        requested_audit_payload,
        requested_audit_payload_digest);
    perform ingestion_quality.iq_complete_batch_command(
        requested_scope_digest, iq_batch.status, iq_batch.aggregate_version,
        iq_processing_at);
    return query select iq_batch.batch_id, 'accepted'::varchar;
    return;
end
$$;

create function ingestion_quality.iq_append_normalized_fact(
    requested_batch_id uuid,
    requested_record_id_utf8 bytea,
    requested_source_id varchar,
    requested_business_key_utf8 bytea,
    requested_source_version bigint,
    requested_source_schema_version varchar,
    requested_source_schema_digest char(71),
    requested_lineage_id uuid,
    requested_content_digest char(71),
    requested_accepted_at timestamptz)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_record_digest char(64);
    iq_business_key_digest char(64);
    iq_existing ingestion_quality.iq_normalized_fact%rowtype;
    iq_inserted_rows integer;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_quality_worker');
    iq_record_digest := encode(sha256(requested_record_id_utf8), 'hex');
    iq_business_key_digest := encode(sha256(requested_business_key_utf8), 'hex');
    insert into ingestion_quality.iq_normalized_fact
      (batch_id, record_id_utf8, record_id_digest, source_id,
       business_key_utf8, business_key_digest, source_version,
       source_schema_version, source_schema_digest, lineage_id,
       content_digest, accepted_at)
    values
      (requested_batch_id, requested_record_id_utf8, iq_record_digest,
       requested_source_id, requested_business_key_utf8, iq_business_key_digest,
       requested_source_version, requested_source_schema_version,
       requested_source_schema_digest, requested_lineage_id,
       requested_content_digest, requested_accepted_at)
    on conflict (batch_id, record_id_digest) do nothing;
    get diagnostics iq_inserted_rows = row_count;
    if iq_inserted_rows = 1 then
        update ingestion_quality.iq_data_batch
           set normalized_fact_count = normalized_fact_count + 1,
               normalized_fact_schema_version = case
                 when normalized_fact_count = 0
                   then requested_source_schema_version
                 else normalized_fact_schema_version end,
               normalized_fact_schema_digest = case
                 when normalized_fact_count = 0
                   then requested_source_schema_digest
                 else normalized_fact_schema_digest end
         where batch_id = requested_batch_id
           and status = 'receiving'
           and normalized_fact_count < 9007199254740991
           and (normalized_fact_count = 0 or (
                normalized_fact_schema_version = requested_source_schema_version
                and normalized_fact_schema_digest = requested_source_schema_digest));
        if not found then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_NORMALIZED_FACT_SCHEMA_INVALID';
        end if;
    end if;
    select * into iq_existing
      from ingestion_quality.iq_normalized_fact
     where batch_id = requested_batch_id and record_id_digest = iq_record_digest;
    if not found
       or iq_existing.record_id_utf8 <> requested_record_id_utf8
       or iq_existing.source_id <> requested_source_id
       or iq_existing.business_key_utf8 <> requested_business_key_utf8
       or iq_existing.source_version <> requested_source_version
       or iq_existing.source_schema_version <> requested_source_schema_version
       or iq_existing.source_schema_digest <> requested_source_schema_digest
       or iq_existing.lineage_id <> requested_lineage_id
       or iq_existing.content_digest <> requested_content_digest
       or iq_existing.accepted_at <> requested_accepted_at then
        raise exception using
            errcode = 'unique_violation',
            message = 'INGESTION_QUALITY_NORMALIZED_FACT_CONFLICT';
    end if;
    return true;
end
$$;

create function ingestion_quality.iq_record_batch_quality_measurement(
    requested_batch_id uuid,
    requested_formula_id varchar,
    requested_formula_ordinal integer,
    requested_applicable boolean,
    requested_operands jsonb,
    requested_evidence_digest char(71),
    requested_recorded_at timestamptz)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_status varchar;
    iq_source_id varchar;
    iq_existing ingestion_quality.iq_batch_quality_measurement%rowtype;
    iq_operand record;
    iq_definition jsonb;
    iq_fixed_applicable boolean;
    iq_derived_applicable boolean;
    iq_expected_operand_count integer;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_quality_worker');
    select status, source_id into iq_status, iq_source_id
      from ingestion_quality.iq_data_batch
     where batch_id = requested_batch_id
     for update;
    select definition, fixed_applicable
      into iq_definition, iq_fixed_applicable
      from ingestion_quality.iq_qmdp_ordered_definitions(iq_source_id)
     where metric_ordinal = requested_formula_ordinal
       and definition ->> 'formulaId' = requested_formula_id;
    with iq_expected(iq_operand_id) as (
      select iq_definition #>> '{calculation,numerator,operandId}'
       where iq_definition #>> '{calculation,numerator,kind}' = 'measured'
      union
      select iq_definition #>> '{calculation,denominator,operandId}'
       where iq_definition #>> '{calculation,denominator,kind}' = 'measured'
    )
    select count(*) into iq_expected_operand_count from iq_expected;
    if iq_status is distinct from 'receiving'
       or iq_definition is null
       or jsonb_typeof(requested_operands) is distinct from 'object' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_MEASUREMENT_EVIDENCE_INVALID';
    end if;
    if (iq_fixed_applicable is not null
           and iq_fixed_applicable <> requested_applicable)
       or (select count(*) from jsonb_object_keys(requested_operands)) > 2
       or (select count(*) from jsonb_object_keys(requested_operands))
             <> iq_expected_operand_count
       or exists (
         select 1
           from jsonb_object_keys(requested_operands) iq_key
          where iq_key not in (
            select iq_definition #>> '{calculation,numerator,operandId}'
             where iq_definition #>> '{calculation,numerator,kind}' = 'measured'
            union
            select iq_definition #>> '{calculation,denominator,operandId}'
             where iq_definition #>> '{calculation,denominator,kind}' = 'measured')) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_MEASUREMENT_EVIDENCE_INVALID';
    end if;
    for iq_operand in select key, value from jsonb_each(requested_operands) loop
        if jsonb_typeof(iq_operand.value) <> 'number'
           or iq_operand.value::text !~ '^(0|[1-9][0-9]*)$'
           or (iq_operand.value::text)::numeric > 9007199254740991 then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_MEASUREMENT_OPERAND_INVALID';
        end if;
    end loop;
    if requested_evidence_digest <> 'sha256:' || encode(sha256(convert_to(
         ingestion_quality.iq_json_canonical(requested_operands), 'UTF8')), 'hex') then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_MEASUREMENT_EVIDENCE_INVALID';
    end if;
    iq_derived_applicable := case
      when iq_fixed_applicable is not null then iq_fixed_applicable
      when iq_definition #>> '{applicability,predicateId}' =
           'overlap-records-present'
        then (requested_operands ->> 'overlap-records')::numeric > 0
      else null
    end;
    if iq_derived_applicable is null
       or requested_applicable <> iq_derived_applicable
       or (not requested_applicable and exists (
         select 1
           from jsonb_each_text(requested_operands) operand(iq_key, iq_value)
          where iq_value::numeric <> 0)) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_MEASUREMENT_EVIDENCE_INVALID';
    end if;
    insert into ingestion_quality.iq_batch_quality_measurement
      (batch_id, formula_id, formula_ordinal, applicable,
       evidence_digest, recorded_at)
    values
      (requested_batch_id, requested_formula_id, requested_formula_ordinal,
       requested_applicable, requested_evidence_digest, requested_recorded_at)
    on conflict (batch_id, formula_id) do nothing;
    select * into iq_existing
      from ingestion_quality.iq_batch_quality_measurement
     where batch_id = requested_batch_id and formula_id = requested_formula_id;
    if not found
       or iq_existing.formula_ordinal <> requested_formula_ordinal
       or iq_existing.applicable <> requested_applicable
       or iq_existing.evidence_digest <> requested_evidence_digest
       or iq_existing.recorded_at <> requested_recorded_at then
        raise exception using
            errcode = 'unique_violation',
            message = 'INGESTION_QUALITY_MEASUREMENT_CONFLICT';
    end if;
    if not exists (
      select 1 from ingestion_quality.iq_batch_quality_operand
       where batch_id = requested_batch_id and formula_id = requested_formula_id) then
        for iq_operand in select key, value from jsonb_each(requested_operands) loop
            insert into ingestion_quality.iq_batch_quality_operand
              (batch_id, formula_id, operand_id, operand_value)
            values
              (requested_batch_id, requested_formula_id, iq_operand.key,
               (iq_operand.value::text)::bigint);
        end loop;
    elsif (select jsonb_object_agg(operand_id, operand_value order by operand_id)
             from ingestion_quality.iq_batch_quality_operand
            where batch_id = requested_batch_id and formula_id = requested_formula_id)
          is distinct from requested_operands then
        raise exception using
            errcode = 'unique_violation',
            message = 'INGESTION_QUALITY_MEASUREMENT_CONFLICT';
    end if;
    return true;
end
$$;

create function ingestion_quality.iq_record_batch_quality_impact_scope(
    requested_batch_id uuid,
    requested_scope_code_utf8 bytea,
    requested_recorded_at timestamptz)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_batch ingestion_quality.iq_data_batch%rowtype;
    iq_existing ingestion_quality.iq_batch_quality_impact_scope%rowtype;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_quality_worker');
    select * into iq_batch
      from ingestion_quality.iq_data_batch
     where batch_id = requested_batch_id
     for update;
    if not found or iq_batch.status <> 'receiving' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_IMPACT_SCOPE_STATE_INVALID';
    end if;
    perform ingestion_quality.iq_require_production_impact_scope(
        iq_batch.source_id, requested_scope_code_utf8);
    if not exists (
         select 1
           from ingestion_quality.iq_batch_quality_impact_scope
          where batch_id = requested_batch_id
            and scope_code_utf8 = requested_scope_code_utf8)
       and (select count(*) >= 64
              from ingestion_quality.iq_batch_quality_impact_scope
             where batch_id = requested_batch_id) then
        raise exception using
            errcode = 'program_limit_exceeded',
            message = 'INGESTION_QUALITY_IMPACT_SCOPE_LIMIT_EXCEEDED';
    end if;
    insert into ingestion_quality.iq_batch_quality_impact_scope
      (batch_id, scope_code_utf8, recorded_at)
    values
      (requested_batch_id, requested_scope_code_utf8, requested_recorded_at)
    on conflict (batch_id, scope_code_utf8) do nothing;
    select * into iq_existing
      from ingestion_quality.iq_batch_quality_impact_scope
     where batch_id = requested_batch_id
       and scope_code_utf8 = requested_scope_code_utf8;
    if not found
       or iq_existing.recorded_at <> requested_recorded_at
       or iq_existing.sealed_at is not null then
        raise exception using
            errcode = 'unique_violation',
            message = 'INGESTION_QUALITY_IMPACT_SCOPE_CONFLICT';
    end if;
    return true;
end
$$;

create function ingestion_quality.iq_seal_data_batch(
    requested_command_id uuid,
    requested_batch_id uuid,
    requested_expected_version bigint,
    requested_record_count bigint,
    requested_valid_record_count bigint,
    requested_rejected_record_count bigint,
    requested_observation_start_at timestamptz,
    requested_observation_end_at timestamptz,
    requested_cutoff_at timestamptz,
    requested_timezone varchar,
    requested_watermark_utf8 bytea,
    requested_source_schema_version varchar,
    requested_source_schema_digest char(71),
    requested_data_catalog_version varchar,
    requested_data_catalog_digest char(71),
    requested_quality_gate_version varchar,
    requested_quality_gate_digest char(71),
    requested_qmdp_version varchar,
    requested_qmdp_digest char(71),
    requested_source_occurred_at timestamptz,
    requested_scheduled_due_at timestamptz,
    requested_received_at timestamptz,
    requested_lane_id varchar,
    requested_manifest_digest char(71),
    requested_contract_evidence jsonb,
    requested_sealed_at timestamptz,
    requested_trace_id char(32),
    requested_scope_digest char(64),
    requested_request_digest char(71),
    requested_audit_payload jsonb,
    requested_audit_payload_digest char(64))
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_batch ingestion_quality.iq_data_batch%rowtype;
    iq_claim varchar;
    iq_measurement_count integer;
    iq_expected_measurement_count integer;
    iq_source jsonb;
    iq_policy jsonb;
    iq_expected_contract jsonb := ingestion_quality.iq_expected_sealed_contract();
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_quality_worker');
    iq_claim := ingestion_quality.iq_claim_batch_command(
        requested_scope_digest, session_user::text, 'seal', requested_request_digest,
        requested_batch_id, requested_sealed_at);
    if iq_claim = 'completed' then return false; end if;
    select * into iq_batch
      from ingestion_quality.iq_data_batch
     where batch_id = requested_batch_id
     for update;
    if not found or iq_batch.status <> 'receiving'
       or iq_batch.aggregate_version <> requested_expected_version
       or iq_batch.declared_manifest_digest <> requested_manifest_digest
       or iq_batch.received_at <> requested_received_at
       or requested_valid_record_count <> iq_batch.normalized_fact_count
       or (iq_batch.normalized_fact_count > 0 and (
            iq_batch.normalized_fact_schema_version <>
                requested_source_schema_version
            or iq_batch.normalized_fact_schema_digest <>
                requested_source_schema_digest)) then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
    perform ingestion_quality.iq_require_production_watermark(
        iq_batch.source_id, requested_watermark_utf8);
    if requested_contract_evidence is distinct from iq_expected_contract then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEALED_CONTRACT_INVALID';
    end if;
    iq_source := ingestion_quality.iq_qmdp_source(iq_batch.source_id);
    select policy into strict iq_policy
      from ingestion_quality.iq_frozen_qmdp_policy
     where singleton;
    if requested_source_schema_version <>
          iq_source #>> '{schemaBinding,version}'
       or requested_source_schema_digest <>
          iq_source #>> '{schemaBinding,canonicalDigest}'
       or requested_data_catalog_version <>
          iq_policy #>> '{controlledInputs,dataCatalog,version}'
       or requested_data_catalog_digest <>
          iq_policy #>> '{controlledInputs,dataCatalog,canonicalDigest}'
       or requested_quality_gate_version <>
          iq_policy #>> '{controlledInputs,qualityGate,version}'
       or requested_quality_gate_digest <>
          iq_policy #>> '{controlledInputs,qualityGate,canonicalDigest}'
       or requested_qmdp_version <> 'QMDP-1.0.0'
       or requested_qmdp_digest <>
          'sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8'
       or requested_timezone <> 'Asia/Shanghai'
       or requested_observation_end_at <> requested_cutoff_at
       or requested_observation_start_at <>
          requested_cutoff_at - interval '720 hours'
       or not exists (
         select 1
           from jsonb_array_elements(iq_source -> 'freshnessLanes') lane(iq_lane)
          where iq_lane ->> 'laneId' = requested_lane_id)
       or requested_sealed_at <
          (iq_expected_contract ->> 'qmdpEffectiveAt')::timestamptz
       or requested_sealed_at <
          (iq_expected_contract ->> 'qshmEffectiveAt')::timestamptz then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
    select count(*) into iq_measurement_count
      from ingestion_quality.iq_batch_quality_measurement
     where batch_id = requested_batch_id
       and sealed_at is null;
    select count(*) into iq_expected_measurement_count
      from ingestion_quality.iq_qmdp_ordered_definitions(iq_batch.source_id);
    if iq_measurement_count <> iq_expected_measurement_count
       or (select min(formula_ordinal)
             from ingestion_quality.iq_batch_quality_measurement
            where batch_id = requested_batch_id) <> 0
       or (select max(formula_ordinal)
             from ingestion_quality.iq_batch_quality_measurement
            where batch_id = requested_batch_id) <>
              iq_expected_measurement_count - 1
       or exists (
         select 1
           from ingestion_quality.iq_qmdp_ordered_definitions(iq_batch.source_id) expected
           left join ingestion_quality.iq_batch_quality_measurement measured
             on measured.batch_id = requested_batch_id
            and measured.formula_ordinal = expected.metric_ordinal
            and measured.formula_id = expected.definition ->> 'formulaId'
          where measured.batch_id is null) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_MEASUREMENT_SET_INCOMPLETE';
    end if;
    if exists (
      select 1
        from ingestion_quality.iq_qmdp_ordered_definitions(iq_batch.source_id) expected
        left join ingestion_quality.iq_batch_quality_operand operand
          on operand.batch_id = requested_batch_id
         and operand.formula_id = expected.definition ->> 'formulaId'
         and operand.operand_id = 'manifest-declared-record-count'
       where expected.definition ->> 'formulaId' = any (array[
         'QMDP-1.0.0/PRIMARY_KEY_COMPLETENESS_BP',
         'QMDP-1.0.0/REQUIRED_FIELD_VALIDITY_BP',
         'QMDP-1.0.0/VALID_RECORD_RATE_BP',
         'QMDP-1.0.0/SCHEMA_ALLOWLIST_COMPATIBILITY_BP',
         'QMDP-1.0.0/SRC-P0-RESPONSIBILITY-001/manifest-reconcile',
         'QMDP-1.0.0/SRC-P1-ACADEMIC-001/manifest-reconcile'])
         and (operand.operand_value is null
              or operand.operand_value <> requested_record_count)) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEAL_EVIDENCE_INVALID';
    end if;
    update ingestion_quality.iq_data_batch
       set status = 'sealed', aggregate_version = requested_expected_version + 1,
           record_count = requested_record_count,
           valid_record_count = requested_valid_record_count,
           rejected_record_count = requested_rejected_record_count,
           observation_start_at = requested_observation_start_at,
           observation_end_at = requested_observation_end_at,
           cutoff_at = requested_cutoff_at, business_timezone = requested_timezone,
           watermark_utf8 = requested_watermark_utf8,
           source_schema_version = requested_source_schema_version,
           source_schema_digest = requested_source_schema_digest,
           data_catalog_version = requested_data_catalog_version,
           data_catalog_digest = requested_data_catalog_digest,
           quality_gate_version = requested_quality_gate_version,
           quality_gate_digest = requested_quality_gate_digest,
           qmdp_version = requested_qmdp_version, qmdp_digest = requested_qmdp_digest,
           source_occurred_at = requested_source_occurred_at,
           scheduled_due_at = requested_scheduled_due_at, lane_id = requested_lane_id,
           sealed_contract_evidence = iq_expected_contract,
           sealed_at = requested_sealed_at
     where batch_id = requested_batch_id and status = 'receiving'
       and aggregate_version = requested_expected_version;
    if not found then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_VERSION_CONFLICT';
    end if;
    update ingestion_quality.iq_batch_quality_measurement
       set sealed_at = requested_sealed_at
     where batch_id = requested_batch_id and sealed_at is null;
    update ingestion_quality.iq_batch_quality_impact_scope
       set sealed_at = requested_sealed_at
     where batch_id = requested_batch_id and sealed_at is null;
    perform ingestion_quality.iq_append_batch_audit(
        requested_command_id, requested_command_id, requested_batch_id,
        'data-batch.seal', 'accepted', requested_expected_version + 1,
        requested_trace_id, requested_request_digest, requested_sealed_at,
        requested_audit_payload, requested_audit_payload_digest);
    perform ingestion_quality.iq_complete_batch_command(
        requested_scope_digest, 'sealed', requested_expected_version + 1,
        requested_sealed_at);
    return true;
end
$$;

create function ingestion_quality.iq_qmdp_expected_metrics(
    requested_batch_id uuid)
returns jsonb
language plpgsql
stable
security definer
strict
set search_path = pg_catalog
as $$
declare
    iq_batch ingestion_quality.iq_data_batch%rowtype;
    iq_expected record;
    iq_measurement ingestion_quality.iq_batch_quality_measurement%rowtype;
    iq_definition jsonb;
    iq_numerator bigint;
    iq_denominator bigint;
    iq_threshold_numerator bigint;
    iq_threshold_denominator bigint;
    iq_value_basis_points bigint;
    iq_passed boolean;
    iq_result text;
    iq_metrics jsonb := '[]'::jsonb;
begin
    select * into iq_batch
      from ingestion_quality.iq_data_batch
     where batch_id = requested_batch_id;
    if not found or iq_batch.status <> 'sealed' or iq_batch.sealed_at is null then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_ASSESSMENT_METRIC_INVALID';
    end if;
    for iq_expected in
      select *
        from ingestion_quality.iq_qmdp_ordered_definitions(iq_batch.source_id)
       order by metric_ordinal
    loop
        iq_definition := iq_expected.definition;
        select * into iq_measurement
          from ingestion_quality.iq_batch_quality_measurement
         where batch_id = requested_batch_id
           and formula_id = iq_definition ->> 'formulaId'
           and formula_ordinal = iq_expected.metric_ordinal
           and sealed_at = iq_batch.sealed_at;
        if not found
           or (iq_expected.fixed_applicable is not null
               and iq_measurement.applicable <> iq_expected.fixed_applicable) then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_ASSESSMENT_POLICY_BINDING_INVALID';
        end if;
        iq_threshold_numerator := (iq_definition ->> 'thresholdNumerator')::bigint;
        iq_threshold_denominator := (iq_definition ->> 'thresholdDenominator')::bigint;
        if not iq_measurement.applicable then
            if exists (
              select 1
                from ingestion_quality.iq_batch_quality_operand
               where batch_id = requested_batch_id
                 and formula_id = iq_measurement.formula_id
                 and operand_value <> 0) then
                raise exception using
                    errcode = 'check_violation',
                    message = 'INGESTION_QUALITY_ASSESSMENT_METRIC_INVALID';
            end if;
            iq_numerator := 0;
            iq_denominator := 0;
            iq_value_basis_points := null;
            iq_result := 'not-applicable';
        else
            select operand_value into iq_numerator
              from ingestion_quality.iq_batch_quality_operand
             where batch_id = requested_batch_id
               and formula_id = iq_measurement.formula_id
               and operand_id = iq_definition #>> '{calculation,numerator,operandId}';
            if not found then
                raise exception using
                    errcode = 'check_violation',
                    message = 'INGESTION_QUALITY_ASSESSMENT_METRIC_INVALID';
            end if;
            if iq_definition #>> '{calculation,denominator,kind}' = 'constant' then
                iq_denominator :=
                    (iq_definition #>> '{calculation,denominator,value}')::bigint;
            else
                select operand_value into iq_denominator
                  from ingestion_quality.iq_batch_quality_operand
                 where batch_id = requested_batch_id
                   and formula_id = iq_measurement.formula_id
                   and operand_id =
                     iq_definition #>> '{calculation,denominator,operandId}';
                if not found then
                    raise exception using
                        errcode = 'check_violation',
                        message = 'INGESTION_QUALITY_ASSESSMENT_METRIC_INVALID';
                end if;
            end if;
            if iq_denominator = 0 then
                raise exception using
                    errcode = 'check_violation',
                    message = 'QUALITY_POLICY_ZERO_DENOMINATOR';
            end if;
            if (iq_definition #>> '{calculation,kind}' in ('count','duration')
                   and iq_denominator <> 1)
               or (iq_definition #>> '{calculation,kind}' = 'composite-and'
                   and iq_numerator > iq_denominator)
               or (iq_definition #>> '{calculation,kind}' = 'ratio'
                   and iq_definition ->> 'formulaId' <>
                     'QMDP-1.0.0/SRC-P0-CALENDAR-001/' ||
                     'calendar-exactly-one-current-day-type'
                   and iq_numerator > iq_denominator) then
                raise exception using
                    errcode = 'check_violation',
                    message = 'INGESTION_QUALITY_ASSESSMENT_METRIC_INVALID';
            end if;
            iq_passed := case iq_definition ->> 'operator'
              when '>=' then iq_numerator::numeric * iq_threshold_denominator >=
                            iq_denominator::numeric * iq_threshold_numerator
              when '<=' then iq_numerator::numeric * iq_threshold_denominator <=
                            iq_denominator::numeric * iq_threshold_numerator
              when '=' then iq_numerator::numeric * iq_threshold_denominator =
                           iq_denominator::numeric * iq_threshold_numerator
              else null
            end;
            if iq_passed is null then
                raise exception using
                    errcode = 'check_violation',
                    message = 'INGESTION_QUALITY_ASSESSMENT_POLICY_BINDING_INVALID';
            end if;
            iq_result := case when iq_passed then 'passed' else 'failed' end;
            iq_value_basis_points := case
              when iq_definition ->> 'unit' in ('basis-point','member-count')
                then round(iq_numerator::numeric * 10000 /
                           iq_denominator::numeric)::bigint
              else null
            end;
        end if;
        iq_metrics := iq_metrics || jsonb_build_array(jsonb_build_object(
          'metricId', iq_definition ->> 'metricId',
          'formulaId', iq_definition ->> 'formulaId',
          'formulaVersion', iq_definition ->> 'formulaVersion',
          'result', iq_result,
          'applicable', iq_measurement.applicable,
          'numerator', iq_numerator,
          'denominator', iq_denominator,
          'valueBasisPoints', iq_value_basis_points,
          'unit', iq_definition ->> 'unit',
          'operator', iq_definition ->> 'operator',
          'thresholdNumerator', iq_threshold_numerator,
          'thresholdDenominator', iq_threshold_denominator,
          'boundary', iq_definition ->> 'boundary',
          'reasonCode', null));
    end loop;
    return iq_metrics;
end
$$;

create function ingestion_quality.iq_qshm_expected_hash(
    requested_batch_id uuid,
    requested_overall_result varchar,
    requested_metrics jsonb)
returns char(71)
language plpgsql
stable
security definer
strict
set search_path = pg_catalog
as $$
declare
    iq_batch ingestion_quality.iq_data_batch%rowtype;
    iq_source jsonb;
    iq_predecessor_snapshot_id uuid;
    iq_impacts jsonb;
    iq_material jsonb;
    iq_canonical text;
begin
    select * into strict iq_batch
      from ingestion_quality.iq_data_batch
     where batch_id = requested_batch_id;
    iq_source := ingestion_quality.iq_qmdp_source(iq_batch.source_id);
    if iq_batch.supersedes_batch_id is not null then
        select snapshot_id into iq_predecessor_snapshot_id
          from ingestion_quality.iq_quality_snapshot
         where batch_id = iq_batch.supersedes_batch_id;
        if iq_predecessor_snapshot_id is null then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_PREDECESSOR_SNAPSHOT_INVALID';
        end if;
    end if;
    select coalesce(jsonb_agg(
             jsonb_build_object('$utf8', encode(scope_code_utf8, 'hex'))
             order by scope_code_utf8), '[]'::jsonb)
      into iq_impacts
      from ingestion_quality.iq_batch_quality_impact_scope
     where batch_id = requested_batch_id
       and sealed_at = iq_batch.sealed_at;
    iq_material := jsonb_build_object(
      'domainTag',
        'scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1',
      'hashProfileVersion', 'QSHM-1.0.0',
      'hashProfileDigest',
        'sha256:cf5837474ae3bd7e82ae05ae9d6a74ea60677566ff6ee59ee780ab5e60f518b2',
      'batchId', iq_batch.batch_id::text,
      'sourceId', iq_batch.source_id,
      'assessedBatchStatus', requested_overall_result,
      'overallResult', requested_overall_result,
      'observationWindow', jsonb_build_object(
        'startAt', ingestion_quality.iq_canonical_instant(iq_batch.observation_start_at),
        'endAt', ingestion_quality.iq_canonical_instant(iq_batch.observation_end_at)),
      'cutoffAt', ingestion_quality.iq_canonical_instant(iq_batch.cutoff_at),
      'watermark', jsonb_build_object('$utf8', encode(iq_batch.watermark_utf8, 'hex')),
      'metricResults', requested_metrics,
      'impactScopeCodes', iq_impacts,
      'sourceOwnerRef', iq_source ->> 'owner',
      'approvalRef', 'AUTH-2026-08-08-001',
      'effectiveAt', ingestion_quality.iq_canonical_instant(
        (ingestion_quality.iq_expected_sealed_contract() ->>
          'qmdpEffectiveAt')::timestamptz),
      'retentionScheduleVersion', 'RS-1.0.0',
      'qualityMetricDecisionProfileVersion', 'QMDP-1.0.0',
      'qualityMetricDecisionProfileDigest',
        'sha256:c574eda413a5d3f7e9e8406f117a91dee8a7874beb82c19166c06584f6051bc8',
      'qualityGateVersion', iq_batch.quality_gate_version,
      'qualityGateDigest', iq_batch.quality_gate_digest,
      'canonicalizationProfile', 'SCHOLARSENSE-CANONICAL-JSON-1.0.0',
      'manifestDigest', iq_batch.declared_manifest_digest,
      'sourceSchemaVersion', iq_batch.source_schema_version,
      'sourceSchemaDigest', iq_batch.source_schema_digest,
      'lineageId', iq_batch.lineage_id::text,
      'supersedesSnapshotId', iq_predecessor_snapshot_id::text);
    iq_canonical := ingestion_quality.iq_qshm_canonical(iq_material);
    return 'sha256:' || encode(sha256(convert_to(iq_canonical, 'UTF8')), 'hex');
end
$$;

create function ingestion_quality.iq_validate_batch_business_payload(
    requested_command_id uuid,
    requested_causation_id uuid,
    requested_event_id uuid,
    requested_batch_id uuid,
    requested_aggregate_version bigint,
    requested_event_type varchar,
    requested_schema_version varchar,
    requested_trace_id char(32),
    requested_occurred_at timestamptz,
    requested_payload_utf8 bytea,
    requested_payload_digest char(64))
returns void
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_payload jsonb;
    iq_data jsonb;
    iq_batch jsonb;
    iq_snapshot jsonb;
    iq_time_valid boolean := false;
    iq_canonical_utf8 bytea;
begin
    if requested_payload_utf8 is null
       or octet_length(requested_payload_utf8) not between 2 and 65536
       or requested_payload_digest is null
       or requested_payload_digest !~ '^[0-9a-f]{64}$' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    begin
        iq_payload := convert_from(requested_payload_utf8, 'UTF8')::jsonb;
    exception when others then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end;
    if not ingestion_quality.iq_json_exact_object(iq_payload, array[
         'data','datacontenttype','id','source','specversion','subject','time',
         'traceparent','type'])
       or jsonb_typeof(iq_payload -> 'data') <> 'object'
       or iq_payload ->> 'specversion' <> '1.0'
       or iq_payload ->> 'id' <> requested_event_id::text
       or iq_payload ->> 'source' <> 'urn:scholarsense:ingestion-quality'
       or requested_event_type <>
          'scholarsense.ingestion-quality.data-batch.quality-assessed.v1'
       or requested_schema_version <> 'DATA-BATCH-QUALITY-ASSESSED-1.0.0'
       or iq_payload ->> 'type' <> requested_event_type
       or iq_payload ->> 'subject' <> 'data-batch/' || requested_batch_id::text
       or iq_payload ->> 'datacontenttype' <> 'application/json'
       or iq_payload ->> 'traceparent' <>
          '00-' || requested_trace_id || '-' || (case
            when substring(requested_trace_id from 1 for 16) ~ '^0{16}$'
              then substring(requested_trace_id from 17 for 16)
            else substring(requested_trace_id from 1 for 16)
          end) || '-01'
       or substring(requested_event_id::text, 15, 1) <> '7'
       or substring(requested_event_id::text, 20, 1) not in ('8','9','a','b') then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    iq_data := iq_payload -> 'data';
    if not ingestion_quality.iq_json_exact_object(iq_data, array[
         'aggregateId','aggregateType','aggregateVersion','causationId',
         'contractVersion','correlationId','eventId','occurredAt','producer',
         'runtimeEvidenceClaim','schemaVersion','traceId','batch','qualitySnapshot'])
       or iq_data ->> 'aggregateId' <> requested_batch_id::text
       or iq_data ->> 'aggregateType' <> 'data-batch'
       or jsonb_typeof(iq_data -> 'aggregateVersion') <> 'number'
       or (iq_data ->> 'aggregateVersion') !~ '^[1-9][0-9]*$'
       or (iq_data ->> 'aggregateVersion')::numeric <> requested_aggregate_version
       or iq_data ->> 'causationId' <> requested_causation_id::text
       or iq_data ->> 'contractVersion' <> 'PIC-1.0.0'
       or iq_data ->> 'correlationId' <> requested_command_id::text
       or iq_data ->> 'eventId' <> requested_event_id::text
       or iq_data ->> 'producer' <> 'ingestion-quality'
       or iq_data ->> 'runtimeEvidenceClaim' <> 'none'
       or iq_data ->> 'schemaVersion' <> requested_schema_version
       or iq_data ->> 'traceId' <> requested_trace_id
       or jsonb_typeof(iq_data -> 'batch') <> 'object'
       or jsonb_typeof(iq_data -> 'qualitySnapshot') <> 'object'
       or (iq_payload ->> 'time') <> (iq_data ->> 'occurredAt') then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    iq_batch := iq_data -> 'batch';
    iq_snapshot := iq_data -> 'qualitySnapshot';
    if not ingestion_quality.iq_json_exact_object(iq_batch, array[
         'batchId','sourceId','sourceVersion','status','aggregateVersion',
         'manifestDigest','observationWindow','cutoffAt','watermark',
         'sourceSchemaVersion','sourceSchemaDigest',
         'qualityMetricDecisionProfileVersion','qualityMetricDecisionProfileDigest',
         'qualityGateVersion','qualityGateDigest','lineageId','supersedesBatchId',
         'effectiveAt','evaluatedAt','publishedAt'])
       or not ingestion_quality.iq_json_exact_object(iq_snapshot, array[
         'snapshotId','batchId','sourceId','assessedBatchStatus','overallResult',
         'observationWindow','cutoffAt','evaluatedAt','watermark','metricResults',
         'impactScopeCodes','sourceOwnerRef','approvalRef','effectiveAt',
         'retentionScheduleVersion','qualityMetricDecisionProfileVersion',
         'qualityMetricDecisionProfileDigest','qualityGateVersion','qualityGateDigest',
         'canonicalizationProfile','manifestDigest','sourceSchemaVersion',
         'sourceSchemaDigest','immutableHash','traceId','lineageId',
         'supersedesSnapshotId','aggregateVersion'])
       or iq_batch ->> 'batchId' <> requested_batch_id::text
       or (iq_batch ->> 'aggregateVersion')::numeric <> requested_aggregate_version
       or iq_batch ->> 'status' not in ('quality-passed','quality-failed')
       or iq_batch -> 'publishedAt' <> 'null'::jsonb
       or iq_snapshot ->> 'batchId' <> requested_batch_id::text
       or (iq_snapshot ->> 'aggregateVersion')::numeric <> requested_aggregate_version
       or iq_snapshot ->> 'assessedBatchStatus' <> iq_batch ->> 'status'
       or iq_snapshot ->> 'overallResult' <> iq_batch ->> 'status'
       or iq_snapshot ->> 'sourceId' <> iq_batch ->> 'sourceId'
       or iq_snapshot ->> 'manifestDigest' <> iq_batch ->> 'manifestDigest'
       or iq_snapshot ->> 'watermark' <> iq_batch ->> 'watermark'
       or iq_snapshot -> 'observationWindow' <> iq_batch -> 'observationWindow'
       or iq_snapshot ->> 'cutoffAt' <> iq_batch ->> 'cutoffAt'
       or iq_snapshot ->> 'qualityMetricDecisionProfileVersion' <>
          iq_batch ->> 'qualityMetricDecisionProfileVersion'
       or iq_snapshot ->> 'qualityMetricDecisionProfileDigest' <>
          iq_batch ->> 'qualityMetricDecisionProfileDigest'
       or iq_snapshot ->> 'qualityGateVersion' <> iq_batch ->> 'qualityGateVersion'
       or iq_snapshot ->> 'qualityGateDigest' <> iq_batch ->> 'qualityGateDigest'
       or iq_snapshot ->> 'sourceSchemaVersion' <> iq_batch ->> 'sourceSchemaVersion'
       or iq_snapshot ->> 'sourceSchemaDigest' <> iq_batch ->> 'sourceSchemaDigest'
       or iq_snapshot ->> 'lineageId' <> iq_batch ->> 'lineageId'
       or iq_snapshot ->> 'traceId' <> requested_trace_id
       or iq_snapshot ->> 'evaluatedAt' <> iq_data ->> 'occurredAt'
       or jsonb_typeof(iq_snapshot -> 'metricResults') <> 'array'
       or jsonb_array_length(iq_snapshot -> 'metricResults') not between 1 and 14
       or jsonb_typeof(iq_snapshot -> 'impactScopeCodes') <> 'array'
       or jsonb_array_length(iq_snapshot -> 'impactScopeCodes') > 64
       or exists (
         select 1 from jsonb_array_elements(iq_snapshot -> 'metricResults') metric(value)
          where not ingestion_quality.iq_json_exact_object(metric.value, array[
            'metricId','formulaId','formulaVersion','result','applicable','numerator',
            'denominator','valueBasisPoints','unit','operator','thresholdNumerator',
            'thresholdDenominator','boundary','reasonCode'])) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    begin
        iq_time_valid := (iq_payload ->> 'time')::timestamptz = requested_occurred_at
            and (iq_data ->> 'occurredAt')::timestamptz = requested_occurred_at;
    exception when others then
        iq_time_valid := false;
    end;
    if not iq_time_valid then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    if encode(sha256(requested_payload_utf8), 'hex') <> requested_payload_digest then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_DIGEST_MISMATCH';
    end if;
    begin
        iq_canonical_utf8 := convert_to(
            ingestion_quality.iq_json_canonical(iq_payload), 'UTF8');
    exception when others then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end;
    if iq_canonical_utf8 <> requested_payload_utf8 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_NOT_CANONICAL';
    end if;
end
$$;

create function ingestion_quality.iq_commit_batch_quality_evaluation(
    requested_command_id uuid,
    requested_batch_id uuid,
    requested_expected_version bigint,
    requested_snapshot_id uuid,
    requested_object_search_token varchar,
    requested_aggregate_search_token varchar,
    requested_overall_result varchar,
    requested_source_owner_ref varchar,
    requested_evaluated_at timestamptz,
    requested_trace_id char(32),
    requested_immutable_hash char(71),
    requested_retention_scope_digest char(71),
    requested_metrics jsonb,
    requested_scope_digest char(64),
    requested_request_digest char(71),
    requested_audit_payload jsonb,
    requested_audit_payload_digest char(64),
    requested_business_event_id uuid,
    requested_business_event_type varchar,
    requested_business_schema_version varchar,
    requested_business_payload_utf8 bytea,
    requested_business_payload_digest char(64))
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_batch ingestion_quality.iq_data_batch%rowtype;
    iq_claim varchar;
    iq_metric record;
    iq_metric_json jsonb;
    iq_metric_count integer := 0;
    iq_measurement_count integer;
    iq_scope record;
    iq_predecessor_snapshot_id uuid;
    iq_assessed_status varchar;
    iq_contract jsonb;
    iq_expected_metrics jsonb;
    iq_expected_metric jsonb;
    iq_expected_overall varchar;
    iq_expected_hash char(71);
    iq_retention_due_at timestamptz;
    iq_retention_scope_material jsonb;
    iq_expected_retention_scope_digest char(71);
    iq_source jsonb;
    iq_causation_id uuid;
    iq_causation_count integer;
    iq_business_payload jsonb;
    iq_expected_impacts jsonb;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_quality_worker');
    if requested_object_search_token !~ '^ost_v1_k[0-9]+_[0-9a-f]{64}$'
       or requested_aggregate_search_token !~ '^agt_v1_k[0-9]+_[0-9a-f]{64}$'
       or split_part(requested_object_search_token, '_', 3) <>
          split_part(requested_aggregate_search_token, '_', 3) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SNAPSHOT_AUDIT_TOKEN_BINDING_INVALID';
    end if;
    iq_claim := ingestion_quality.iq_claim_batch_command(
        requested_scope_digest, session_user::text, 'evaluate',
        requested_request_digest, requested_batch_id, requested_evaluated_at);
    if iq_claim = 'completed' then
        if not exists (
          select 1
            from ingestion_quality.iq_quality_snapshot_audit_token_binding binding
           where binding.snapshot_id = requested_snapshot_id
             and binding.object_search_token = requested_object_search_token
             and binding.aggregate_search_token = requested_aggregate_search_token) then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_SNAPSHOT_AUDIT_TOKEN_BINDING_INVALID';
        end if;
        return false;
    end if;
    select * into iq_batch
      from ingestion_quality.iq_data_batch
     where batch_id = requested_batch_id
     for update;
    if not found or iq_batch.status <> 'sealed'
       or iq_batch.aggregate_version <> requested_expected_version
       or iq_batch.sealed_contract_evidence is null then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_VERSION_CONFLICT';
    end if;
    iq_contract := iq_batch.sealed_contract_evidence;
    if iq_contract is distinct from ingestion_quality.iq_expected_sealed_contract() then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SEALED_CONTRACT_INVALID';
    end if;
    if requested_evaluated_at < iq_batch.sealed_at then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_ASSESSMENT_METRIC_INVALID';
    end if;
    iq_expected_metrics :=
        ingestion_quality.iq_qmdp_expected_metrics(requested_batch_id);
    iq_measurement_count := jsonb_array_length(iq_expected_metrics);
    if jsonb_typeof(requested_metrics) is distinct from 'array' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_ASSESSMENT_FORMULA_SET_INVALID';
    end if;
    if jsonb_array_length(requested_metrics) <> iq_measurement_count then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_ASSESSMENT_FORMULA_SET_INVALID';
    end if;
    for iq_metric in
      select value, ordinality
        from jsonb_array_elements(requested_metrics) with ordinality
    loop
        iq_metric_json := iq_metric.value;
        iq_expected_metric := iq_expected_metrics -> (iq_metric.ordinality - 1)::integer;
        if not ingestion_quality.iq_json_exact_object(iq_metric_json, array[
             'metricId','formulaId','formulaVersion','result','applicable',
             'numerator','denominator','valueBasisPoints','unit','operator',
             'thresholdNumerator','thresholdDenominator','boundary','reasonCode'])
           or jsonb_typeof(iq_metric_json -> 'metricId') <> 'string'
           or jsonb_typeof(iq_metric_json -> 'formulaId') <> 'string'
           or jsonb_typeof(iq_metric_json -> 'formulaVersion') <> 'string'
           or jsonb_typeof(iq_metric_json -> 'result') <> 'string'
           or jsonb_typeof(iq_metric_json -> 'applicable') <> 'boolean'
           or jsonb_typeof(iq_metric_json -> 'numerator') <> 'number'
           or (iq_metric_json ->> 'numerator') !~ '^(0|[1-9][0-9]*)$'
           or (iq_metric_json ->> 'numerator')::numeric > 9007199254740991
           or jsonb_typeof(iq_metric_json -> 'denominator') <> 'number'
           or (iq_metric_json ->> 'denominator') !~ '^(0|[1-9][0-9]*)$'
           or (iq_metric_json ->> 'denominator')::numeric > 9007199254740991
           or not (jsonb_typeof(iq_metric_json -> 'valueBasisPoints') = 'null'
             or (jsonb_typeof(iq_metric_json -> 'valueBasisPoints') = 'number'
               and (iq_metric_json ->> 'valueBasisPoints') ~ '^(0|[1-9][0-9]*)$'
               and (iq_metric_json ->> 'valueBasisPoints')::numeric <= 9007199254740991))
           or jsonb_typeof(iq_metric_json -> 'unit') <> 'string'
           or jsonb_typeof(iq_metric_json -> 'operator') <> 'string'
           or jsonb_typeof(iq_metric_json -> 'thresholdNumerator') <> 'number'
           or (iq_metric_json ->> 'thresholdNumerator') !~ '^(0|[1-9][0-9]*)$'
           or (iq_metric_json ->> 'thresholdNumerator')::numeric > 9007199254740991
           or jsonb_typeof(iq_metric_json -> 'thresholdDenominator') <> 'number'
           or (iq_metric_json ->> 'thresholdDenominator') !~ '^[1-9][0-9]*$'
           or (iq_metric_json ->> 'thresholdDenominator')::numeric > 9007199254740991
           or jsonb_typeof(iq_metric_json -> 'boundary') <> 'string'
           or jsonb_typeof(iq_metric_json -> 'reasonCode') <> 'null' then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_ASSESSMENT_METRIC_INVALID';
        end if;
        if iq_metric_json ->> 'formulaId' <>
           iq_expected_metric ->> 'formulaId' then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_ASSESSMENT_FORMULA_SET_INVALID';
        end if;
        if iq_metric_json ->> 'metricId' <> iq_expected_metric ->> 'metricId'
           or iq_metric_json ->> 'formulaVersion' <>
              iq_expected_metric ->> 'formulaVersion'
           or (iq_metric_json ->> 'applicable')::boolean <>
              (iq_expected_metric ->> 'applicable')::boolean
           or iq_metric_json ->> 'unit' <> iq_expected_metric ->> 'unit'
           or iq_metric_json ->> 'operator' <> iq_expected_metric ->> 'operator'
           or (iq_metric_json ->> 'thresholdNumerator')::numeric <>
              (iq_expected_metric ->> 'thresholdNumerator')::numeric
           or (iq_metric_json ->> 'thresholdDenominator')::numeric <>
              (iq_expected_metric ->> 'thresholdDenominator')::numeric
           or iq_metric_json ->> 'boundary' <> iq_expected_metric ->> 'boundary' then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_ASSESSMENT_POLICY_BINDING_INVALID';
        end if;
        if iq_metric_json is distinct from iq_expected_metric then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_ASSESSMENT_METRIC_INVALID';
        end if;
    end loop;
    iq_expected_overall := case when exists (
      select 1
        from jsonb_array_elements(iq_expected_metrics) metric(iq_value)
       where iq_value ->> 'result' = 'failed')
      then 'quality-failed' else 'quality-passed' end;
    if requested_overall_result is distinct from iq_expected_overall then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_ASSESSMENT_OVERALL_INVALID';
    end if;
    iq_source := ingestion_quality.iq_qmdp_source(iq_batch.source_id);
    if requested_source_owner_ref is distinct from (iq_source ->> 'owner') then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_ASSESSMENT_POLICY_BINDING_INVALID';
    end if;
    iq_expected_hash := ingestion_quality.iq_qshm_expected_hash(
        requested_batch_id, iq_expected_overall, iq_expected_metrics);
    if requested_immutable_hash is distinct from iq_expected_hash then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_ASSESSMENT_HASH_INVALID';
    end if;
    -- Task 0 retention scope is independent of QSHM. Runtime instants are UTC,
    -- omit a zero fraction, and otherwise emit the database's full six microseconds.
    iq_retention_due_at :=
        ((requested_evaluated_at at time zone 'UTC') + interval '2 years')
        at time zone 'UTC';
    iq_retention_scope_material := jsonb_build_object(
        'objectType', 'QualitySnapshot',
        'snapshotId', requested_snapshot_id::text,
        'sourceId', iq_batch.source_id,
        'snapshotAggregateVersion', requested_expected_version + 1,
        'evaluatedAt', case
          when date_trunc('second', requested_evaluated_at) = requested_evaluated_at
            then to_char(requested_evaluated_at at time zone 'UTC',
                         'YYYY-MM-DD"T"HH24:MI:SS"Z"')
          else ingestion_quality.iq_canonical_instant(requested_evaluated_at)
        end,
        'retentionDueAt', case
          when date_trunc('second', iq_retention_due_at) = iq_retention_due_at
            then to_char(iq_retention_due_at at time zone 'UTC',
                         'YYYY-MM-DD"T"HH24:MI:SS"Z"')
          else ingestion_quality.iq_canonical_instant(iq_retention_due_at)
        end,
        'snapshotImmutableHash', iq_expected_hash::text,
        'retentionPolicyVersion', 'QUALITY-SNAPSHOT-RETENTION-1.0.0',
        'retentionScheduleVersion', 'RS-1.0.0');
    iq_expected_retention_scope_digest := 'sha256:' || encode(sha256(convert_to(
        ingestion_quality.iq_json_canonical(iq_retention_scope_material),
        'UTF8')), 'hex');
    if requested_retention_scope_digest is distinct from
       iq_expected_retention_scope_digest then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RETENTION_SCOPE_DIGEST_MISMATCH';
    end if;
    select count(*), min(audit_id::text)::uuid
      into iq_causation_count, iq_causation_id
      from ingestion_quality.iq_local_audit_fact
     where batch_id = requested_batch_id
       and action = 'data-batch.seal'
       and result = 'accepted'
       and aggregate_version = requested_expected_version;
    if iq_causation_count <> 1 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    perform ingestion_quality.iq_validate_batch_business_payload(
        requested_command_id, iq_causation_id, requested_business_event_id,
        requested_batch_id, requested_expected_version + 1,
        requested_business_event_type, requested_business_schema_version,
        requested_trace_id, requested_evaluated_at,
        requested_business_payload_utf8, requested_business_payload_digest);
    iq_business_payload := convert_from(requested_business_payload_utf8, 'UTF8')::jsonb;
    if iq_batch.supersedes_batch_id is not null then
        select snapshot_id into iq_predecessor_snapshot_id
          from ingestion_quality.iq_quality_snapshot
         where batch_id = iq_batch.supersedes_batch_id;
        if iq_predecessor_snapshot_id is null then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_PREDECESSOR_SNAPSHOT_INVALID';
        end if;
    end if;
    select coalesce(jsonb_agg(
             convert_from(scope_code_utf8, 'UTF8') order by scope_code_utf8),
             '[]'::jsonb)
      into iq_expected_impacts
      from ingestion_quality.iq_batch_quality_impact_scope
     where batch_id = requested_batch_id
       and sealed_at = iq_batch.sealed_at;
    begin
        if iq_business_payload #> '{data,batch,sourceVersion}' is distinct from
             to_jsonb(iq_batch.source_version)
           or iq_business_payload #>> '{data,batch,sourceId}' <> iq_batch.source_id
           or iq_business_payload #>> '{data,batch,status}' <> requested_overall_result
           or iq_business_payload #>> '{data,batch,manifestDigest}' <>
              iq_batch.declared_manifest_digest
           or (iq_business_payload #>> '{data,batch,observationWindow,startAt}')::timestamptz
              is distinct from iq_batch.observation_start_at
           or (iq_business_payload #>> '{data,batch,observationWindow,endAt}')::timestamptz
              is distinct from iq_batch.observation_end_at
           or (iq_business_payload #>> '{data,batch,cutoffAt}')::timestamptz
              is distinct from iq_batch.cutoff_at
           or iq_business_payload #>> '{data,batch,watermark}' <>
              convert_from(iq_batch.watermark_utf8, 'UTF8')
           or iq_business_payload #>> '{data,batch,sourceSchemaVersion}' <>
              iq_batch.source_schema_version
           or iq_business_payload #>> '{data,batch,sourceSchemaDigest}' <>
              iq_batch.source_schema_digest
           or iq_business_payload #>>
              '{data,batch,qualityMetricDecisionProfileVersion}' <> iq_batch.qmdp_version
           or iq_business_payload #>>
              '{data,batch,qualityMetricDecisionProfileDigest}' <> iq_batch.qmdp_digest
           or iq_business_payload #>> '{data,batch,qualityGateVersion}' <>
              iq_batch.quality_gate_version
           or iq_business_payload #>> '{data,batch,qualityGateDigest}' <>
              iq_batch.quality_gate_digest
           or iq_business_payload #>> '{data,batch,lineageId}' <>
              iq_batch.lineage_id::text
           or iq_business_payload #> '{data,batch,supersedesBatchId}' is distinct from
              coalesce(to_jsonb(iq_batch.supersedes_batch_id::text), 'null'::jsonb)
           or (iq_business_payload #>> '{data,batch,effectiveAt}')::timestamptz
              is distinct from iq_batch.effective_at
           or (iq_business_payload #>> '{data,batch,evaluatedAt}')::timestamptz
              is distinct from requested_evaluated_at
           or iq_business_payload #> '{data,batch,publishedAt}' is distinct from 'null'::jsonb
           or iq_business_payload #>> '{data,qualitySnapshot,snapshotId}' <>
              requested_snapshot_id::text
           or iq_business_payload #>> '{data,qualitySnapshot,sourceId}' <>
              iq_batch.source_id
           or iq_business_payload #>> '{data,qualitySnapshot,immutableHash}' <>
              iq_expected_hash::text
           or iq_business_payload #> '{data,qualitySnapshot,metricResults}' is distinct from
              iq_expected_metrics
           or iq_business_payload #> '{data,qualitySnapshot,impactScopeCodes}' is distinct from
              iq_expected_impacts
           or iq_business_payload #>> '{data,qualitySnapshot,overallResult}' <>
              requested_overall_result
           or iq_business_payload #>> '{data,qualitySnapshot,sourceOwnerRef}' <>
              requested_source_owner_ref
           or iq_business_payload #>> '{data,qualitySnapshot,approvalRef}' <>
              iq_contract ->> 'qmdpApprovalRef'
           or (iq_business_payload #>> '{data,qualitySnapshot,effectiveAt}')::timestamptz
              is distinct from (iq_contract ->> 'qmdpEffectiveAt')::timestamptz
           or iq_business_payload #>>
              '{data,qualitySnapshot,retentionScheduleVersion}' <> 'RS-1.0.0'
           or iq_business_payload #>>
              '{data,qualitySnapshot,canonicalizationProfile}' <>
              'SCHOLARSENSE-CANONICAL-JSON-1.0.0'
           or iq_business_payload #> '{data,qualitySnapshot,supersedesSnapshotId}'
              is distinct from coalesce(
                to_jsonb(iq_predecessor_snapshot_id::text), 'null'::jsonb) then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_BUSINESS_SNAPSHOT_BINDING_INVALID';
        end if;
    exception when others then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_SNAPSHOT_BINDING_INVALID';
    end;
    iq_assessed_status := requested_overall_result;
    insert into ingestion_quality.iq_quality_snapshot
      (snapshot_id, batch_id, domain_tag, hash_profile_version,
       hash_profile_digest, source_id, assessed_batch_status, overall_result,
       observation_start_at, observation_end_at, cutoff_at, watermark_utf8,
       source_owner_ref, approval_ref, effective_at, retention_schedule_version,
       qmdp_version, qmdp_digest, quality_gate_version, quality_gate_digest,
       canonicalization_profile, manifest_digest, source_schema_version,
       source_schema_digest, lineage_id, supersedes_snapshot_id, evaluated_at,
       trace_id, aggregate_version, immutable_hash, retention_due_at,
       retention_scope_digest)
    values
      (requested_snapshot_id, requested_batch_id,
       'scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1',
       iq_contract ->> 'qshmProfileVersion',
       iq_contract ->> 'qshmProfileCanonicalDigest', iq_batch.source_id,
       iq_assessed_status, requested_overall_result, iq_batch.observation_start_at,
       iq_batch.observation_end_at, iq_batch.cutoff_at, iq_batch.watermark_utf8,
       requested_source_owner_ref, iq_contract ->> 'qmdpApprovalRef',
       (iq_contract ->> 'qmdpEffectiveAt')::timestamptz, 'RS-1.0.0',
       iq_contract ->> 'qmdpProfileVersion',
       iq_contract ->> 'qmdpPolicyCanonicalDigest', iq_batch.quality_gate_version,
       iq_batch.quality_gate_digest, 'SCHOLARSENSE-CANONICAL-JSON-1.0.0',
       iq_batch.declared_manifest_digest, iq_batch.source_schema_version,
       iq_batch.source_schema_digest, iq_batch.lineage_id,
       iq_predecessor_snapshot_id, requested_evaluated_at, requested_trace_id,
       requested_expected_version + 1, requested_immutable_hash,
       iq_retention_due_at, iq_expected_retention_scope_digest);
    insert into ingestion_quality.iq_quality_snapshot_audit_token_binding
      (snapshot_id, object_search_token, aggregate_search_token, key_version)
    values
      (requested_snapshot_id, requested_object_search_token,
       requested_aggregate_search_token,
       split_part(requested_object_search_token, '_', 3));
    for iq_metric in
      select value, ordinality
        from jsonb_array_elements(iq_expected_metrics) with ordinality
    loop
        iq_metric_json := iq_metric.value;
        insert into ingestion_quality.iq_quality_snapshot_metric
          (snapshot_id, metric_ordinal, metric_id, formula_id, formula_version,
           result, applicable, numerator, denominator, value_basis_points,
           unit, operator, threshold_numerator, threshold_denominator,
           boundary, reason_code)
        values
          (requested_snapshot_id, iq_metric.ordinality - 1,
           iq_metric_json ->> 'metricId', iq_metric_json ->> 'formulaId',
           iq_metric_json ->> 'formulaVersion', iq_metric_json ->> 'result',
           (iq_metric_json ->> 'applicable')::boolean,
           (iq_metric_json ->> 'numerator')::bigint,
           (iq_metric_json ->> 'denominator')::bigint,
           case when iq_metric_json -> 'valueBasisPoints' = 'null'::jsonb
             then null else (iq_metric_json ->> 'valueBasisPoints')::bigint end,
           iq_metric_json ->> 'unit', iq_metric_json ->> 'operator',
           (iq_metric_json ->> 'thresholdNumerator')::bigint,
           (iq_metric_json ->> 'thresholdDenominator')::bigint,
           iq_metric_json ->> 'boundary', iq_metric_json ->> 'reasonCode');
        iq_metric_count := iq_metric_count + 1;
    end loop;
    if iq_metric_count <> iq_measurement_count then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_ASSESSMENT_FORMULA_SET_INVALID';
    end if;
    for iq_scope in
      select iq_frozen_scope.scope_code_utf8,
             row_number() over (
               order by iq_frozen_scope.scope_code_utf8) - 1 as scope_ordinal
        from ingestion_quality.iq_batch_quality_impact_scope iq_frozen_scope
       where iq_frozen_scope.batch_id = requested_batch_id
         and iq_frozen_scope.sealed_at = iq_batch.sealed_at
       order by iq_frozen_scope.scope_code_utf8
    loop
        insert into ingestion_quality.iq_quality_snapshot_impact_scope
          (snapshot_id, scope_ordinal, scope_code_utf8)
        values
          (requested_snapshot_id, iq_scope.scope_ordinal,
           iq_scope.scope_code_utf8);
    end loop;
    update ingestion_quality.iq_data_batch
       set status = iq_assessed_status,
           aggregate_version = requested_expected_version + 1,
           evaluated_at = requested_evaluated_at
     where batch_id = requested_batch_id and status = 'sealed'
       and aggregate_version = requested_expected_version;
    if not found then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_VERSION_CONFLICT';
    end if;
    perform ingestion_quality.iq_append_batch_audit(
        requested_command_id, requested_command_id, requested_batch_id,
        'data-batch.evaluate', 'accepted', requested_expected_version + 1,
        requested_trace_id, requested_request_digest, requested_evaluated_at,
        requested_audit_payload, requested_audit_payload_digest);
    insert into ingestion_quality.iq_batch_quality_outbox
      (event_id, aggregate_id, aggregate_version, event_type, schema_version,
       payload_utf8, payload_digest, available_at, created_at)
    values
      (requested_business_event_id, requested_batch_id,
       requested_expected_version + 1, requested_business_event_type,
       requested_business_schema_version, requested_business_payload_utf8,
       requested_business_payload_digest, requested_evaluated_at,
       requested_evaluated_at);
    perform ingestion_quality.iq_complete_batch_command(
        requested_scope_digest, iq_assessed_status,
        requested_expected_version + 1, requested_evaluated_at);
    return true;
end
$$;

create function ingestion_quality.iq_validate_batch_published_payload(
    requested_command_id uuid,
    requested_causation_id uuid,
    requested_event_id uuid,
    requested_batch_id uuid,
    requested_aggregate_version bigint,
    requested_event_type varchar,
    requested_schema_version varchar,
    requested_trace_id char(32),
    requested_occurred_at timestamptz,
    requested_payload_utf8 bytea,
    requested_payload_digest char(64))
returns void
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_payload_text text;
    iq_payload jsonb;
    iq_data jsonb;
    iq_batch jsonb;
    iq_snapshot jsonb;
    iq_time_valid boolean := false;
    iq_canonical_utf8 bytea;
begin
    if requested_command_id is null
       or requested_causation_id is null
       or requested_event_id is null
       or requested_batch_id is null
       or requested_aggregate_version is null
       or requested_aggregate_version not between 1 and 9007199254740991
       or requested_event_type is null
       or requested_schema_version is null
       or requested_trace_id is null
       or requested_occurred_at is null
       or requested_payload_utf8 is null
       or octet_length(requested_payload_utf8) not between 2 and 65536
       or requested_payload_digest is null
       or requested_payload_digest !~ '^[0-9a-f]{64}$' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    if encode(sha256(requested_payload_utf8), 'hex') <>
       requested_payload_digest then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_DIGEST_MISMATCH';
    end if;
    begin
        iq_payload_text := convert_from(requested_payload_utf8, 'UTF8');
        if (iq_payload_text is json object with unique keys) is not true then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
        end if;
        iq_payload := iq_payload_text::jsonb;
        iq_canonical_utf8 := convert_to(
            ingestion_quality.iq_json_canonical(iq_payload), 'UTF8');
    exception when others then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end;
    if iq_canonical_utf8 <> requested_payload_utf8 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_NOT_CANONICAL';
    end if;
    if ingestion_quality.iq_json_exact_object(iq_payload, array[
         'data','datacontenttype','id','source','specversion','subject','time',
         'traceparent','type']) is not true
       or jsonb_typeof(iq_payload -> 'data') <> 'object'
       or iq_payload ->> 'specversion' <> '1.0'
       or iq_payload ->> 'id' <> requested_event_id::text
       or iq_payload ->> 'source' <> 'urn:scholarsense:ingestion-quality'
       or requested_event_type <>
          'scholarsense.ingestion-quality.data-batch.published.v1'
       or requested_schema_version <> 'DATA-BATCH-PUBLISHED-1.0.0'
       or iq_payload ->> 'type' <> requested_event_type
       or iq_payload ->> 'subject' <> 'data-batch/' || requested_batch_id::text
       or iq_payload ->> 'datacontenttype' <> 'application/json'
       or iq_payload ->> 'traceparent' <>
          '00-' || requested_trace_id || '-' || (case
            when substring(requested_trace_id from 1 for 16) ~ '^0{16}$'
              then substring(requested_trace_id from 17 for 16)
            else substring(requested_trace_id from 1 for 16)
          end) || '-01'
       or substring(requested_command_id::text, 15, 1) <> '7'
       or substring(requested_command_id::text, 20, 1) not in ('8','9','a','b')
       or substring(requested_causation_id::text, 15, 1) <> '7'
       or substring(requested_causation_id::text, 20, 1) not in ('8','9','a','b')
       or substring(requested_event_id::text, 15, 1) <> '7'
       or substring(requested_event_id::text, 20, 1) not in ('8','9','a','b') then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    iq_data := iq_payload -> 'data';
    if ingestion_quality.iq_json_exact_object(iq_data, array[
         'aggregateId','aggregateType','aggregateVersion','causationId',
         'contractVersion','correlationId','eventId','occurredAt','producer',
         'runtimeEvidenceClaim','schemaVersion','traceId','batch','qualitySnapshot']) is not true
       or iq_data ->> 'aggregateId' <> requested_batch_id::text
       or iq_data ->> 'aggregateType' <> 'data-batch'
       or jsonb_typeof(iq_data -> 'aggregateVersion') <> 'number'
       or (iq_data ->> 'aggregateVersion') !~ '^[1-9][0-9]*$'
       or (iq_data ->> 'aggregateVersion')::numeric <>
          requested_aggregate_version
       or iq_data ->> 'causationId' <> requested_causation_id::text
       or iq_data ->> 'contractVersion' <> 'PIC-1.0.0'
       or iq_data ->> 'correlationId' <> requested_command_id::text
       or iq_data ->> 'eventId' <> requested_event_id::text
       or iq_data ->> 'producer' <> 'ingestion-quality'
       or iq_data ->> 'runtimeEvidenceClaim' <> 'none'
       or iq_data ->> 'schemaVersion' <> requested_schema_version
       or iq_data ->> 'traceId' <> requested_trace_id
       or jsonb_typeof(iq_data -> 'batch') <> 'object'
       or jsonb_typeof(iq_data -> 'qualitySnapshot') <> 'object'
       or iq_payload ->> 'time' <> iq_data ->> 'occurredAt' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    iq_batch := iq_data -> 'batch';
    iq_snapshot := iq_data -> 'qualitySnapshot';
    if not ingestion_quality.iq_json_exact_object(iq_batch, array[
         'batchId','sourceId','sourceVersion','status','aggregateVersion',
         'manifestDigest','observationWindow','cutoffAt','watermark',
         'sourceSchemaVersion','sourceSchemaDigest',
         'qualityMetricDecisionProfileVersion','qualityMetricDecisionProfileDigest',
         'qualityGateVersion','qualityGateDigest','lineageId','supersedesBatchId',
         'effectiveAt','evaluatedAt','publishedAt'])
       or not ingestion_quality.iq_json_exact_object(iq_snapshot, array[
         'snapshotId','batchId','sourceId','assessedBatchStatus','overallResult',
         'observationWindow','cutoffAt','evaluatedAt','watermark','metricResults',
         'impactScopeCodes','sourceOwnerRef','approvalRef','effectiveAt',
         'retentionScheduleVersion','qualityMetricDecisionProfileVersion',
         'qualityMetricDecisionProfileDigest','qualityGateVersion','qualityGateDigest',
         'canonicalizationProfile','manifestDigest','sourceSchemaVersion',
         'sourceSchemaDigest','immutableHash','traceId','lineageId',
         'supersedesSnapshotId','aggregateVersion'])
       or iq_batch ->> 'batchId' <> requested_batch_id::text
       or (iq_batch ->> 'aggregateVersion')::numeric <> requested_aggregate_version
       or iq_batch ->> 'status' <> 'published'
       or iq_batch ->> 'publishedAt' <> iq_data ->> 'occurredAt'
       or iq_snapshot ->> 'batchId' <> requested_batch_id::text
       or (iq_snapshot ->> 'aggregateVersion')::numeric <>
          requested_aggregate_version - 1
       or iq_snapshot ->> 'assessedBatchStatus' <> 'quality-passed'
       or iq_snapshot ->> 'overallResult' <> 'quality-passed'
       or iq_snapshot ->> 'sourceId' <> iq_batch ->> 'sourceId'
       or iq_snapshot ->> 'manifestDigest' <> iq_batch ->> 'manifestDigest'
       or iq_snapshot ->> 'watermark' <> iq_batch ->> 'watermark'
       or iq_snapshot -> 'observationWindow' <> iq_batch -> 'observationWindow'
       or iq_snapshot ->> 'cutoffAt' <> iq_batch ->> 'cutoffAt'
       or iq_snapshot ->> 'qualityMetricDecisionProfileVersion' <>
          iq_batch ->> 'qualityMetricDecisionProfileVersion'
       or iq_snapshot ->> 'qualityMetricDecisionProfileDigest' <>
          iq_batch ->> 'qualityMetricDecisionProfileDigest'
       or iq_snapshot ->> 'qualityGateVersion' <> iq_batch ->> 'qualityGateVersion'
       or iq_snapshot ->> 'qualityGateDigest' <> iq_batch ->> 'qualityGateDigest'
       or iq_snapshot ->> 'sourceSchemaVersion' <> iq_batch ->> 'sourceSchemaVersion'
       or iq_snapshot ->> 'sourceSchemaDigest' <> iq_batch ->> 'sourceSchemaDigest'
       or iq_snapshot ->> 'lineageId' <> iq_batch ->> 'lineageId'
       or jsonb_typeof(iq_snapshot -> 'metricResults') <> 'array'
       or jsonb_array_length(iq_snapshot -> 'metricResults') not between 1 and 14
       or jsonb_typeof(iq_snapshot -> 'impactScopeCodes') <> 'array'
       or jsonb_array_length(iq_snapshot -> 'impactScopeCodes') > 64
       or exists (
         select 1 from jsonb_array_elements(iq_snapshot -> 'metricResults') metric(value)
          where not ingestion_quality.iq_json_exact_object(metric.value, array[
            'metricId','formulaId','formulaVersion','result','applicable','numerator',
            'denominator','valueBasisPoints','unit','operator','thresholdNumerator',
            'thresholdDenominator','boundary','reasonCode'])) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    begin
        iq_time_valid := (iq_payload ->> 'time')::timestamptz =
                requested_occurred_at
            and (iq_data ->> 'occurredAt')::timestamptz =
                requested_occurred_at;
    exception when others then
        iq_time_valid := false;
    end;
    if not iq_time_valid then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
end
$$;

create function ingestion_quality.iq_publish_data_batch(
    requested_command_id uuid,
    requested_batch_id uuid,
    requested_expected_version bigint,
    requested_published_at timestamptz,
    requested_trace_id char(32),
    requested_scope_digest char(64),
    requested_request_digest char(71),
    requested_audit_payload jsonb,
    requested_audit_payload_digest char(64),
    requested_business_event_id uuid,
    requested_business_event_type varchar,
    requested_business_schema_version varchar,
    requested_business_payload_utf8 bytea,
    requested_business_payload_digest char(64))
returns boolean
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_batch ingestion_quality.iq_data_batch%rowtype;
    iq_claim varchar;
    iq_causation_id uuid;
    iq_causation_count integer;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_quality_worker');
    select count(*), min(audit_id::text)::uuid
      into iq_causation_count, iq_causation_id
      from ingestion_quality.iq_local_audit_fact
     where batch_id = requested_batch_id
       and action = 'data-batch.evaluate'
       and result = 'accepted'
       and aggregate_version = requested_expected_version;
    if iq_causation_count <> 1 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_BUSINESS_PAYLOAD_INVALID';
    end if;
    perform ingestion_quality.iq_validate_batch_published_payload(
        requested_command_id, iq_causation_id, requested_business_event_id,
        requested_batch_id, requested_expected_version + 1,
        requested_business_event_type, requested_business_schema_version,
        requested_trace_id, requested_published_at,
        requested_business_payload_utf8, requested_business_payload_digest);
    iq_claim := ingestion_quality.iq_claim_batch_command(
        requested_scope_digest, session_user::text, 'publish',
        requested_request_digest, requested_batch_id, requested_published_at);
    if iq_claim = 'completed' then return false; end if;
    select * into iq_batch
      from ingestion_quality.iq_data_batch
     where batch_id = requested_batch_id
     for update;
    if not found or iq_batch.status <> 'quality-passed'
       or iq_batch.aggregate_version <> requested_expected_version
       or not exists (
         select 1 from ingestion_quality.iq_quality_snapshot
          where batch_id = requested_batch_id
            and aggregate_version = requested_expected_version)
       or iq_batch.valid_record_count <> iq_batch.normalized_fact_count then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_PUBLISH_STATE_INVALID';
    end if;
    update ingestion_quality.iq_data_batch
       set status = 'published', aggregate_version = requested_expected_version + 1,
           published_at = requested_published_at
     where batch_id = requested_batch_id and status = 'quality-passed'
       and aggregate_version = requested_expected_version;
    if not found then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_VERSION_CONFLICT';
    end if;
    perform ingestion_quality.iq_append_batch_audit(
        requested_command_id, requested_command_id, requested_batch_id,
        'data-batch.publish', 'accepted', requested_expected_version + 1,
        requested_trace_id, requested_request_digest, requested_published_at,
        requested_audit_payload, requested_audit_payload_digest);
    insert into ingestion_quality.iq_batch_quality_outbox
      (event_id, aggregate_id, aggregate_version, event_type, schema_version,
       payload_utf8, payload_digest, available_at, created_at)
    values
      (requested_business_event_id, requested_batch_id,
       requested_expected_version + 1, requested_business_event_type,
       requested_business_schema_version, requested_business_payload_utf8,
       requested_business_payload_digest, requested_published_at,
       requested_published_at);
    perform ingestion_quality.iq_complete_batch_command(
        requested_scope_digest, 'published', requested_expected_version + 1,
        requested_published_at);
    return true;
end
$$;

create function ingestion_quality.iq_json_uuid_v7(input_value jsonb)
returns boolean
language sql
immutable
strict
set search_path = pg_catalog
as $$
select jsonb_typeof(input_value) = 'string'
   and input_value #>> '{}' ~
       '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
$$;

create function ingestion_quality.iq_json_digest(input_value jsonb)
returns boolean
language sql
immutable
strict
set search_path = pg_catalog
as $$
select jsonb_typeof(input_value) = 'string'
   and input_value #>> '{}' ~ '^sha256:[0-9a-f]{64}$'
$$;

create function ingestion_quality.iq_json_safe_nonnegative_integer(input_value jsonb)
returns boolean
language sql
immutable
strict
set search_path = pg_catalog
as $$
select jsonb_typeof(input_value) = 'number'
   and input_value::text ~ '^(0|[1-9][0-9]*)$'
   and input_value::text::numeric <= 9007199254740991
$$;

create function ingestion_quality.iq_json_timestamp(input_value jsonb)
returns boolean
language plpgsql
stable
strict
set search_path = pg_catalog
as $$
declare
    iq_text text;
begin
    if jsonb_typeof(input_value) <> 'string' then return false; end if;
    iq_text := input_value #>> '{}';
    if iq_text !~
       '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})$' then
        return false;
    end if;
    perform iq_text::timestamptz;
    return true;
exception when others then
    return false;
end
$$;

create function ingestion_quality.iq_deletion_result_target_shape(input_value jsonb)
returns boolean
language sql
stable
strict
set search_path = pg_catalog
as $$
select ingestion_quality.iq_json_exact_object(input_value, array[
           'status','selectedCount','deletedCount','remainingCount',
           'evidenceDigest','errorCode','transactionId','transactionEvidenceDigest'])
   and jsonb_typeof(input_value -> 'status') = 'string'
   and ingestion_quality.iq_json_safe_nonnegative_integer(
           input_value -> 'selectedCount')
   and ingestion_quality.iq_json_safe_nonnegative_integer(
           input_value -> 'deletedCount')
   and ingestion_quality.iq_json_safe_nonnegative_integer(
           input_value -> 'remainingCount')
   and ((input_value -> 'evidenceDigest') = 'null'::jsonb
        or ingestion_quality.iq_json_digest(input_value -> 'evidenceDigest'))
   and ((input_value -> 'errorCode') = 'null'::jsonb
        or (jsonb_typeof(input_value -> 'errorCode') = 'string'
            and input_value ->> 'errorCode' in (
              'INDEX_DELETE_FAILED','OBJECT_DELETE_FAILED','OWNER_STORE_UNAVAILABLE')))
   and ((input_value -> 'transactionId') = 'null'::jsonb
        or ingestion_quality.iq_json_uuid_v7(input_value -> 'transactionId'))
   and ((input_value -> 'transactionEvidenceDigest') = 'null'::jsonb
        or ingestion_quality.iq_json_digest(
             input_value -> 'transactionEvidenceDigest'))
$$;

create function ingestion_quality.iq_deletion_result_payload_shape(input_value jsonb)
returns boolean
language plpgsql
stable
strict
set search_path = pg_catalog
as $$
declare
    iq_data jsonb;
    iq_scope jsonb;
    iq_guards jsonb;
    iq_trusted jsonb;
    iq_hold jsonb;
    iq_registry jsonb;
    iq_authority jsonb;
    iq_owner_results jsonb;
    iq_backup jsonb;
    iq_handoff jsonb;
    iq_item jsonb;
    iq_attestation jsonb;
    iq_result text;
    iq_target_name text;
begin
    if ingestion_quality.iq_json_exact_object(input_value, array[
         'specversion','id','source','type','subject','time','datacontenttype',
         'traceparent','data']) is not true then
        return false;
    end if;
    iq_data := input_value -> 'data';
    if ingestion_quality.iq_json_exact_object(iq_data, array[
         'resultContractVersion','contractVersion','correlationId','causationId',
         'eventId','aggregateType','aggregateId','aggregateVersion','executionId',
         'supersedesResultId','producer','occurredAt','traceId','result',
         'retentionPolicyVersion','retentionScheduleVersion','scope','guards',
         'ownerLocalResults','deletionCommittedAt','blockerCodes','failureCodes',
         'backup','auditHandoff','canonicalizationProfile','runtimeEvidenceClaim'])
       is not true then
        return false;
    end if;
    if input_value ->> 'specversion' <> '1.0'
       or not ingestion_quality.iq_json_uuid_v7(input_value -> 'id')
       or input_value ->> 'source' <> 'urn:scholarsense:ingestion-quality'
       or input_value ->> 'type' <>
          'scholarsense.ingestion-quality.quality-snapshot.deletion-result.v1'
       or jsonb_typeof(input_value -> 'subject') <> 'string'
       or not ingestion_quality.iq_json_timestamp(input_value -> 'time')
       or input_value ->> 'datacontenttype' <> 'application/json'
       or jsonb_typeof(input_value -> 'traceparent') <> 'string'
       or input_value ->> 'traceparent' !~
          '^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$'
       or iq_data ->> 'resultContractVersion' <>
          'QUALITY-SNAPSHOT-DELETION-RESULT-1.0.0'
       or iq_data ->> 'contractVersion' <> 'PIC-1.0.0'
       or not ingestion_quality.iq_json_uuid_v7(iq_data -> 'correlationId')
       or not ingestion_quality.iq_json_uuid_v7(iq_data -> 'causationId')
       or not ingestion_quality.iq_json_uuid_v7(iq_data -> 'eventId')
       or iq_data ->> 'aggregateType' <> 'quality-snapshot-deletion-execution'
       or not ingestion_quality.iq_json_uuid_v7(iq_data -> 'aggregateId')
       or not ingestion_quality.iq_json_safe_nonnegative_integer(
            iq_data -> 'aggregateVersion')
       or (iq_data ->> 'aggregateVersion')::numeric < 1
       or not ingestion_quality.iq_json_uuid_v7(iq_data -> 'executionId')
       or not ((iq_data -> 'supersedesResultId') = 'null'::jsonb
               or ingestion_quality.iq_json_uuid_v7(
                    iq_data -> 'supersedesResultId'))
       or iq_data ->> 'producer' <> 'ingestion-quality-retention-executor'
       or not ingestion_quality.iq_json_timestamp(iq_data -> 'occurredAt')
       or jsonb_typeof(iq_data -> 'traceId') <> 'string'
       or iq_data ->> 'traceId' !~ '^(?!0{32}$)[0-9a-f]{32}$'
       or iq_data ->> 'retentionPolicyVersion' <>
          'QUALITY-SNAPSHOT-RETENTION-1.0.0'
       or iq_data ->> 'retentionScheduleVersion' <> 'RS-1.0.0'
       or iq_data ->> 'canonicalizationProfile' <>
          'SCHOLARSENSE-CANONICAL-JSON-1.0.0'
       or iq_data ->> 'runtimeEvidenceClaim' <> 'none' then
        return false;
    end if;
    iq_result := iq_data ->> 'result';
    if iq_result not in ('blocked','completed') then return false; end if;

    iq_scope := iq_data -> 'scope';
    if ingestion_quality.iq_json_exact_object(iq_scope, array[
         'objectType','snapshotId','sourceId','snapshotAggregateVersion',
         'evaluatedAt','retentionDueAt','snapshotImmutableHash','scopeDigest'])
       is not true
       or iq_scope ->> 'objectType' <> 'QualitySnapshot'
       or not ingestion_quality.iq_json_uuid_v7(iq_scope -> 'snapshotId')
       or jsonb_typeof(iq_scope -> 'sourceId') <> 'string'
       or iq_scope ->> 'sourceId' !~ '^SRC-P[01]-[A-Z-]+-[0-9]{3}$'
       or not ingestion_quality.iq_json_safe_nonnegative_integer(
            iq_scope -> 'snapshotAggregateVersion')
       or (iq_scope ->> 'snapshotAggregateVersion')::numeric < 1
       or not ingestion_quality.iq_json_timestamp(iq_scope -> 'evaluatedAt')
       or not ingestion_quality.iq_json_timestamp(iq_scope -> 'retentionDueAt')
       or not ingestion_quality.iq_json_digest(iq_scope -> 'snapshotImmutableHash')
       or not ingestion_quality.iq_json_digest(iq_scope -> 'scopeDigest') then
        return false;
    end if;

    iq_guards := iq_data -> 'guards';
    if ingestion_quality.iq_json_exact_object(iq_guards, array[
         'trustedTime','legalHold','consumerRegistry',
         'consumerWatermarksCheckedAt','consumerWatermarks']) is not true then
        return false;
    end if;
    iq_trusted := iq_guards -> 'trustedTime';
    if ingestion_quality.iq_json_exact_object(
         iq_trusted, array['status','observedAt']) is not true
       or iq_trusted ->> 'status' not in ('available','unavailable')
       or not ((iq_trusted -> 'observedAt') = 'null'::jsonb
               or ingestion_quality.iq_json_timestamp(
                    iq_trusted -> 'observedAt')) then
        return false;
    end if;
    iq_hold := iq_guards -> 'legalHold';
    if ingestion_quality.iq_json_exact_object(iq_hold, array[
         'status','checkedScopeDigest','matchedCount','matchedScopeDigests','checkedAt'])
       is not true
       or iq_hold ->> 'status' not in ('clear','matched','unavailable')
       or not ingestion_quality.iq_json_digest(iq_hold -> 'checkedScopeDigest')
       or not ingestion_quality.iq_json_safe_nonnegative_integer(
            iq_hold -> 'matchedCount')
       or jsonb_typeof(iq_hold -> 'matchedScopeDigests') <> 'array'
       or jsonb_array_length(iq_hold -> 'matchedScopeDigests') > 32
       or not ((iq_hold -> 'checkedAt') = 'null'::jsonb
               or ingestion_quality.iq_json_timestamp(iq_hold -> 'checkedAt')) then
        return false;
    end if;
    if (select count(*) <> count(distinct value)
          from jsonb_array_elements(iq_hold -> 'matchedScopeDigests')) then
        return false;
    end if;
    for iq_item in select value from jsonb_array_elements(
      iq_hold -> 'matchedScopeDigests') loop
        if not ingestion_quality.iq_json_digest(iq_item) then return false; end if;
    end loop;

    iq_registry := iq_guards -> 'consumerRegistry';
    if ingestion_quality.iq_json_exact_object(iq_registry, array[
         'status','registryVersion','registryDigest','members','conformanceAnchorId',
         'checkedAt','authorityEvidence']) is not true
       or iq_registry ->> 'status' not in ('available','unavailable')
       or iq_registry ->> 'registryVersion' <>
          'QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0'
       or not ingestion_quality.iq_json_digest(iq_registry -> 'registryDigest')
       or jsonb_typeof(iq_registry -> 'members') <> 'array'
       or jsonb_array_length(iq_registry -> 'members') > 32
       or not ((iq_registry -> 'conformanceAnchorId') = 'null'::jsonb
               or (jsonb_typeof(iq_registry -> 'conformanceAnchorId') = 'string'
                   and iq_registry ->> 'conformanceAnchorId' in (
                     'active-clue-care-consumer-set',
                     'inactive-legacy-holder-consumer-set','empty-consumer-set')))
       or not ((iq_registry -> 'checkedAt') = 'null'::jsonb
               or ingestion_quality.iq_json_timestamp(iq_registry -> 'checkedAt')) then
        return false;
    end if;
    if (select count(*) <> count(distinct value)
          from jsonb_array_elements(iq_registry -> 'members')) then
        return false;
    end if;
    for iq_item in select value from jsonb_array_elements(
      iq_registry -> 'members') loop
        if ingestion_quality.iq_json_exact_object(iq_item, array[
             'consumerId','registryMembership','lifecycleStatus']) is not true
           or jsonb_typeof(iq_item -> 'consumerId') <> 'string'
           or iq_item ->> 'consumerId' !~ '^[a-z][a-z0-9-]{2,79}$'
           or iq_item ->> 'registryMembership' not in (
             'required-for-snapshot','unreleased-reference-holder',
             'planned-never-held-reference')
           or iq_item ->> 'lifecycleStatus' not in (
             'active','inactive','planned','retired') then
            return false;
        end if;
    end loop;
    iq_authority := iq_registry -> 'authorityEvidence';
    if iq_authority <> 'null'::jsonb then
        if ingestion_quality.iq_json_exact_object(iq_authority, array[
             'provider','evidenceRef','scopeDigest','registryVersion','registryDigest',
             'membersDigest','checkedAt','verificationStatus','runtimeEvidenceClaim'])
           is not true
           or iq_authority ->> 'provider' <>
              'quality-snapshot-consumer-registry-conformance-anchor'
           or jsonb_typeof(iq_authority -> 'evidenceRef') <> 'string'
           or iq_authority ->> 'evidenceRef' !~
              '^contract://quality-snapshot-retention/consumer-registry/(active-clue-care-consumer-set|inactive-legacy-holder-consumer-set|empty-consumer-set)$'
           or not ingestion_quality.iq_json_digest(iq_authority -> 'scopeDigest')
           or iq_authority ->> 'registryVersion' <>
              'QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0'
           or not ingestion_quality.iq_json_digest(iq_authority -> 'registryDigest')
           or not ingestion_quality.iq_json_digest(iq_authority -> 'membersDigest')
           or not ingestion_quality.iq_json_timestamp(iq_authority -> 'checkedAt')
           or iq_authority ->> 'verificationStatus' <> 'contract-conformance'
           or iq_authority ->> 'runtimeEvidenceClaim' <> 'none' then
            return false;
        end if;
    end if;

    if not ((iq_guards -> 'consumerWatermarksCheckedAt') = 'null'::jsonb
            or ingestion_quality.iq_json_timestamp(
                 iq_guards -> 'consumerWatermarksCheckedAt'))
       or jsonb_typeof(iq_guards -> 'consumerWatermarks') <> 'array'
       or jsonb_array_length(iq_guards -> 'consumerWatermarks') > 32 then
        return false;
    end if;
    if (select count(*) <> count(distinct value)
          from jsonb_array_elements(iq_guards -> 'consumerWatermarks')) then
        return false;
    end if;
    for iq_item in select value from jsonb_array_elements(
      iq_guards -> 'consumerWatermarks') loop
        if ingestion_quality.iq_json_exact_object(iq_item, array[
             'consumerId','registryMembership','lifecycleStatus','watermarkStatus',
             'requiredAggregateVersion','confirmedAggregateVersion','transportAck',
             'inboxAck','evidenceCopyAck','attestation']) is not true
           or jsonb_typeof(iq_item -> 'consumerId') <> 'string'
           or iq_item ->> 'consumerId' !~ '^[a-z][a-z0-9-]{2,79}$'
           or iq_item ->> 'registryMembership' not in (
             'required-for-snapshot','unreleased-reference-holder',
             'planned-never-held-reference')
           or iq_item ->> 'lifecycleStatus' not in (
             'active','inactive','planned','retired')
           or iq_item ->> 'watermarkStatus' not in ('confirmed','missing','unknown')
           or not ingestion_quality.iq_json_safe_nonnegative_integer(
                iq_item -> 'requiredAggregateVersion')
           or (iq_item ->> 'requiredAggregateVersion')::numeric < 1
           or not ((iq_item -> 'confirmedAggregateVersion') = 'null'::jsonb
                   or ingestion_quality.iq_json_safe_nonnegative_integer(
                        iq_item -> 'confirmedAggregateVersion'))
           or iq_item ->> 'transportAck' not in ('acked','missing')
           or iq_item ->> 'inboxAck' not in ('acked','missing')
           or iq_item ->> 'evidenceCopyAck' not in (
             'copied','not-required','missing','unavailable') then
            return false;
        end if;
        iq_attestation := iq_item -> 'attestation';
        if iq_attestation <> 'null'::jsonb then
            if ingestion_quality.iq_json_exact_object(iq_attestation, array[
                 'kind','snapshotId','snapshotImmutableHash','requiredAggregateVersion',
                 'consumerId','registryVersion','registryDigest','scopeDigest','attestedAt'])
               is not true
               or iq_attestation ->> 'kind' not in (
                 'evidence-copy','zero-dependency-no-reference',
                 'decommission-no-reference')
               or not ingestion_quality.iq_json_uuid_v7(
                    iq_attestation -> 'snapshotId')
               or not ingestion_quality.iq_json_digest(
                    iq_attestation -> 'snapshotImmutableHash')
               or not ingestion_quality.iq_json_safe_nonnegative_integer(
                    iq_attestation -> 'requiredAggregateVersion')
               or (iq_attestation ->> 'requiredAggregateVersion')::numeric < 1
               or jsonb_typeof(iq_attestation -> 'consumerId') <> 'string'
               or iq_attestation ->> 'consumerId' !~ '^[a-z][a-z0-9-]{2,79}$'
               or iq_attestation ->> 'registryVersion' <>
                  'QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0'
               or not ingestion_quality.iq_json_digest(
                    iq_attestation -> 'registryDigest')
               or not ingestion_quality.iq_json_digest(
                    iq_attestation -> 'scopeDigest')
               or not ingestion_quality.iq_json_timestamp(
                    iq_attestation -> 'attestedAt') then
                return false;
            end if;
        end if;
    end loop;

    iq_owner_results := iq_data -> 'ownerLocalResults';
    if ingestion_quality.iq_json_exact_object(iq_owner_results, array[
         'onlineSnapshot','onlineMetrics','readModels','indexes','caches','objects'])
       is not true then
        return false;
    end if;
    foreach iq_target_name in array array[
      'onlineSnapshot','onlineMetrics','readModels','indexes','caches','objects'] loop
        if ingestion_quality.iq_deletion_result_target_shape(
             iq_owner_results -> iq_target_name) is not true then
            return false;
        end if;
    end loop;

    if jsonb_typeof(iq_data -> 'blockerCodes') <> 'array'
       or jsonb_typeof(iq_data -> 'failureCodes') <> 'array'
       or (select count(*) <> count(distinct value)
             from jsonb_array_elements(iq_data -> 'blockerCodes'))
       or (select count(*) <> count(distinct value)
             from jsonb_array_elements(iq_data -> 'failureCodes')) then
        return false;
    end if;
    for iq_item in select value from jsonb_array_elements(
      iq_data -> 'blockerCodes') loop
        if jsonb_typeof(iq_item) <> 'string'
           or iq_item #>> '{}' not in (
             'LEGAL_HOLD_MATCHED','RETENTION_NOT_DUE','CONSUMER_WATERMARK_BEHIND',
             'CONSUMER_WATERMARK_MISSING','CONSUMER_WATERMARK_UNKNOWN',
             'EVIDENCE_COPY_ACK_MISSING','TRUSTED_TIME_UNAVAILABLE',
             'LEGAL_HOLD_DEPENDENCY_UNAVAILABLE','WATERMARK_DEPENDENCY_UNAVAILABLE',
             'CONSUMER_REGISTRY_UNAVAILABLE') then
            return false;
        end if;
    end loop;
    for iq_item in select value from jsonb_array_elements(
      iq_data -> 'failureCodes') loop
        if jsonb_typeof(iq_item) <> 'string'
           or iq_item #>> '{}' not in (
             'INDEX_DELETE_FAILED','OBJECT_DELETE_FAILED','OWNER_STORE_UNAVAILABLE') then
            return false;
        end if;
    end loop;
    if iq_result = 'completed' then
        if jsonb_array_length(iq_data -> 'blockerCodes') <> 0
           or jsonb_array_length(iq_data -> 'failureCodes') <> 0
           or not ingestion_quality.iq_json_timestamp(
                iq_data -> 'deletionCommittedAt') then
            return false;
        end if;
    else
        if jsonb_array_length(iq_data -> 'blockerCodes') not between 1 and 10
           or jsonb_array_length(iq_data -> 'failureCodes') <> 0
           or iq_data -> 'deletionCommittedAt' <> 'null'::jsonb then
            return false;
        end if;
    end if;

    iq_backup := iq_data -> 'backup';
    if ingestion_quality.iq_json_exact_object(iq_backup, array[
         'policyVersion','maximumRetentionDays','backupExpiryDueAt',
         'physicalDeletionClaim']) is not true
       or iq_backup ->> 'policyVersion' <> 'DRP-1.0.0'
       or iq_backup -> 'maximumRetentionDays' <> '35'::jsonb
       or iq_backup ->> 'physicalDeletionClaim' <> 'none'
       or not ((iq_backup -> 'backupExpiryDueAt') = 'null'::jsonb
               or ingestion_quality.iq_json_timestamp(
                    iq_backup -> 'backupExpiryDueAt')) then
        return false;
    end if;
    iq_handoff := iq_data -> 'auditHandoff';
    if ingestion_quality.iq_json_exact_object(iq_handoff, array[
         'targetOwner','inputKind','finalReceiptOwner','ownerResultIsFinalReceipt',
         'conformanceReceiptSatisfiesProduction']) is not true
       or iq_handoff ->> 'targetOwner' <> 'audit-operations'
       or iq_handoff ->> 'inputKind' <> 'owner-local-deletion-result'
       or iq_handoff ->> 'finalReceiptOwner' <> 'audit-operations'
       or iq_handoff -> 'ownerResultIsFinalReceipt' <> 'false'::jsonb
       or iq_handoff -> 'conformanceReceiptSatisfiesProduction' <> 'false'::jsonb then
        return false;
    end if;
    return true;
exception when others then
    return false;
end
$$;

drop function if exists ingestion_quality.iq_execute_quality_snapshot_retention(
    uuid, uuid, uuid, character, character, uuid, bytea, character);

create function ingestion_quality.iq_retention_authority_payload_shape(input_value jsonb)
returns boolean
language plpgsql
immutable
strict
set search_path = pg_catalog
as $$
declare
    iq_member jsonb;
    iq_attestation jsonb;
begin
    if ingestion_quality.iq_json_exact_object(input_value, array[
         'authorityEvidenceId','provider','evidenceRef','scope','scopeDigest',
         'registryVersion','registryDigest','membersDigest','members','legalHold',
         'watermarksCheckedAt','trustedObservedAt','issuedAt','expiresAt']) is not true
       or not ingestion_quality.iq_json_uuid_v7(input_value -> 'authorityEvidenceId')
       or input_value ->> 'provider' <> 'consumer-registry-authority'
       or jsonb_typeof(input_value -> 'evidenceRef') <> 'string'
       or input_value ->> 'evidenceRef' !~
          '^consumer-registry-authority://[A-Za-z0-9._~:/?#@!$&()*+,;=%-]+$'
       or not ingestion_quality.iq_json_digest(input_value -> 'scopeDigest')
       or input_value ->> 'registryVersion' <>
          'QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0'
       or not ingestion_quality.iq_json_digest(input_value -> 'registryDigest')
       or not ingestion_quality.iq_json_digest(input_value -> 'membersDigest')
       or jsonb_typeof(input_value -> 'members') <> 'array'
       or jsonb_array_length(input_value -> 'members') > 32
       or not ingestion_quality.iq_json_timestamp(input_value -> 'watermarksCheckedAt')
       or not ingestion_quality.iq_json_timestamp(input_value -> 'trustedObservedAt')
       or not ingestion_quality.iq_json_timestamp(input_value -> 'issuedAt')
       or not ingestion_quality.iq_json_timestamp(input_value -> 'expiresAt') then
        return false;
    end if;
    if ingestion_quality.iq_json_exact_object(input_value -> 'scope', array[
         'objectType','snapshotId','sourceId','snapshotAggregateVersion','evaluatedAt',
         'retentionDueAt','snapshotImmutableHash','retentionPolicyVersion',
         'retentionScheduleVersion']) is not true
       or input_value #>> '{scope,objectType}' <> 'QualitySnapshot'
       or not ingestion_quality.iq_json_uuid_v7(input_value #> '{scope,snapshotId}')
       or jsonb_typeof(input_value #> '{scope,sourceId}') <> 'string'
       or not ingestion_quality.iq_json_safe_nonnegative_integer(
            input_value #> '{scope,snapshotAggregateVersion}')
       or (input_value #>> '{scope,snapshotAggregateVersion}')::numeric < 1
       or not ingestion_quality.iq_json_timestamp(input_value #> '{scope,evaluatedAt}')
       or not ingestion_quality.iq_json_timestamp(input_value #> '{scope,retentionDueAt}')
       or not ingestion_quality.iq_json_digest(
            input_value #> '{scope,snapshotImmutableHash}')
       or input_value #>> '{scope,retentionPolicyVersion}' <>
          'QUALITY-SNAPSHOT-RETENTION-1.0.0'
       or input_value #>> '{scope,retentionScheduleVersion}' <> 'RS-1.0.0' then
        return false;
    end if;
    if ingestion_quality.iq_json_exact_object(input_value -> 'legalHold', array[
         'status','checkedScopeDigest','checkedAt']) is not true
       or input_value #>> '{legalHold,status}' not in ('clear','matched')
       or not ingestion_quality.iq_json_digest(
            input_value #> '{legalHold,checkedScopeDigest}')
       or not ingestion_quality.iq_json_timestamp(
            input_value #> '{legalHold,checkedAt}') then
        return false;
    end if;
    for iq_member in select value from jsonb_array_elements(
      input_value -> 'members') loop
        if ingestion_quality.iq_json_exact_object(iq_member, array[
             'consumerId','registryMembership','lifecycleStatus','watermarkStatus',
             'requiredAggregateVersion','confirmedAggregateVersion','transportAck',
             'inboxAck','evidenceCopyAck','attestation']) is not true
           or jsonb_typeof(iq_member -> 'consumerId') <> 'string'
           or iq_member ->> 'consumerId' !~ '^[a-z][a-z0-9-]{2,79}$'
           or iq_member ->> 'registryMembership' not in (
             'required-for-snapshot','unreleased-reference-holder',
             'planned-never-held-reference')
           or iq_member ->> 'lifecycleStatus' not in (
             'active','inactive','planned','retired')
           or iq_member ->> 'watermarkStatus' not in ('confirmed','missing','unknown')
           or not ingestion_quality.iq_json_safe_nonnegative_integer(
                iq_member -> 'requiredAggregateVersion')
           or (iq_member ->> 'requiredAggregateVersion')::numeric < 1
           or not ((iq_member -> 'confirmedAggregateVersion') = 'null'::jsonb
                   or ingestion_quality.iq_json_safe_nonnegative_integer(
                        iq_member -> 'confirmedAggregateVersion'))
           or iq_member ->> 'transportAck' not in ('acked','missing')
           or iq_member ->> 'inboxAck' not in ('acked','missing')
           or iq_member ->> 'evidenceCopyAck' not in (
                'copied','not-required','missing','unavailable') then
            return false;
        end if;
        iq_attestation := iq_member -> 'attestation';
        if iq_attestation <> 'null'::jsonb then
            if ingestion_quality.iq_json_exact_object(iq_attestation, array[
                 'kind','snapshotId','snapshotImmutableHash',
                 'requiredAggregateVersion','consumerId','registryVersion',
                 'registryDigest','scopeDigest','attestedAt']) is not true
               or iq_attestation ->> 'kind' not in (
                 'evidence-copy','zero-dependency-no-reference',
                 'decommission-no-reference')
               or not ingestion_quality.iq_json_uuid_v7(
                    iq_attestation -> 'snapshotId')
               or not ingestion_quality.iq_json_digest(
                    iq_attestation -> 'snapshotImmutableHash')
               or not ingestion_quality.iq_json_safe_nonnegative_integer(
                    iq_attestation -> 'requiredAggregateVersion')
               or jsonb_typeof(iq_attestation -> 'consumerId') <> 'string'
               or iq_attestation ->> 'registryVersion' <>
                  'QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0'
               or not ingestion_quality.iq_json_digest(
                    iq_attestation -> 'registryDigest')
               or not ingestion_quality.iq_json_digest(
                    iq_attestation -> 'scopeDigest')
               or not ingestion_quality.iq_json_timestamp(
                    iq_attestation -> 'attestedAt') then
                return false;
            end if;
        end if;
    end loop;
    return true;
exception when others then
    return false;
end
$$;

create function ingestion_quality.iq_ingest_quality_snapshot_retention_authority(
    requested_authority_evidence_id uuid,
    requested_payload_utf8 bytea,
    requested_payload_digest char(64))
returns uuid
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_payload_text text;
    iq_payload jsonb;
    iq_scope jsonb;
    iq_members jsonb;
    iq_sorted_members jsonb;
    iq_member_projection jsonb;
    iq_attestations_payload jsonb;
    iq_attestations_utf8 bytea;
    iq_snapshot ingestion_quality.iq_quality_snapshot%rowtype;
    iq_existing ingestion_quality.iq_quality_snapshot_retention_authority_evidence%rowtype;
    iq_member jsonb;
    iq_attestation jsonb;
    iq_expected_scope jsonb;
    iq_expected_scope_digest char(71);
    iq_expected_members_digest char(71);
    iq_expected_registry_digest char(71);
    iq_evidence_copy_digest char(71);
    iq_evidence_copy_status varchar(32);
    iq_issued_at timestamptz;
    iq_expires_at timestamptz;
    iq_trusted_observed_at timestamptz;
    iq_legal_hold_checked_at timestamptz;
    iq_watermarks_checked_at timestamptz;
    iq_attested_at timestamptz;
    iq_statement_at timestamptz := pg_catalog.statement_timestamp();
    iq_inserted bigint;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_consumer_registry_authority');
    if requested_authority_evidence_id is null
       or substring(requested_authority_evidence_id::text, 15, 1) <> '7'
       or substring(requested_authority_evidence_id::text, 20, 1)
          not in ('8','9','a','b')
       or requested_payload_utf8 is null
       or octet_length(requested_payload_utf8) not between 2 and 65536
       or requested_payload_digest is null
       or requested_payload_digest !~ '^[0-9a-f]{64}$'
       or requested_payload_digest <> encode(sha256(requested_payload_utf8), 'hex') then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_INVALID';
    end if;
    begin
        if octet_length(requested_payload_utf8) >= 3
           and get_byte(requested_payload_utf8, 0) = 239
           and get_byte(requested_payload_utf8, 1) = 187
           and get_byte(requested_payload_utf8, 2) = 191 then
            raise data_exception;
        end if;
        if ingestion_quality.iq_utf8_scalar_count(requested_payload_utf8) < 0 then
            raise data_exception;
        end if;
        iq_payload_text := convert_from(requested_payload_utf8, 'UTF8');
        if (iq_payload_text is json object with unique keys) is not true then
            raise data_exception;
        end if;
        iq_payload := iq_payload_text::jsonb;
        perform ingestion_quality.iq_json_canonical(iq_payload);
        if ingestion_quality.iq_retention_authority_payload_shape(iq_payload)
           is not true then
            raise data_exception;
        end if;
    exception when others then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_INVALID';
    end;
    iq_scope := iq_payload -> 'scope';
    iq_members := iq_payload -> 'members';
    if iq_payload ->> 'authorityEvidenceId' <>
          requested_authority_evidence_id::text
       or iq_payload ->> 'evidenceRef' <>
          'consumer-registry-authority://production/quality-snapshot/' ||
          requested_authority_evidence_id::text
       or (iq_scope ->> 'snapshotId')::uuid = requested_authority_evidence_id then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_INVALID';
    end if;
    -- Authority IDs and deletion-result event IDs share one global identity
    -- namespace. Lock before the cross-table check so concurrent ingest and
    -- execute transactions cannot each observe the other's ID as absent.
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        requested_authority_evidence_id::text, 23030));
    if exists (
        select 1
          from ingestion_quality.iq_quality_snapshot_deletion_result result
         where result.result_event_id = requested_authority_evidence_id) then
        raise exception using
            errcode = 'unique_violation',
            message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_ID_CONFLICT';
    end if;
    select * into iq_snapshot
      from ingestion_quality.iq_quality_snapshot
     where snapshot_id = (iq_scope ->> 'snapshotId')::uuid
     for share;
    if not found then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_INVALID';
    end if;
    iq_expected_scope := jsonb_build_object(
        'objectType','QualitySnapshot',
        'snapshotId',iq_snapshot.snapshot_id::text,
        'sourceId',iq_snapshot.source_id,
        'snapshotAggregateVersion',iq_snapshot.aggregate_version,
        'evaluatedAt',case
          when date_trunc('second', iq_snapshot.evaluated_at) = iq_snapshot.evaluated_at
            then to_char(iq_snapshot.evaluated_at at time zone 'UTC',
                         'YYYY-MM-DD"T"HH24:MI:SS"Z"')
          else ingestion_quality.iq_canonical_instant(iq_snapshot.evaluated_at)
        end,
        'retentionDueAt',case
          when date_trunc('second', iq_snapshot.retention_due_at) = iq_snapshot.retention_due_at
            then to_char(iq_snapshot.retention_due_at at time zone 'UTC',
                         'YYYY-MM-DD"T"HH24:MI:SS"Z"')
          else ingestion_quality.iq_canonical_instant(iq_snapshot.retention_due_at)
        end,
        'snapshotImmutableHash',iq_snapshot.immutable_hash::text,
        'retentionPolicyVersion','QUALITY-SNAPSHOT-RETENTION-1.0.0',
        'retentionScheduleVersion',iq_snapshot.retention_schedule_version);
    iq_expected_scope_digest := 'sha256:' || encode(sha256(convert_to(
        ingestion_quality.iq_json_canonical(iq_expected_scope), 'UTF8')), 'hex');
    if iq_scope <> iq_expected_scope
       or iq_payload ->> 'scopeDigest' <> iq_expected_scope_digest
       or iq_snapshot.retention_scope_digest <> iq_expected_scope_digest then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_INVALID';
    end if;

    select coalesce(jsonb_agg(value order by (value ->> 'consumerId') collate "C"),
                    '[]'::jsonb)
      into iq_sorted_members
      from jsonb_array_elements(iq_members) member(value);
    select coalesce(jsonb_agg(jsonb_build_object(
               'consumerId',value -> 'consumerId',
               'registryMembership',value -> 'registryMembership',
               'lifecycleStatus',value -> 'lifecycleStatus')
               order by (value ->> 'consumerId') collate "C"), '[]'::jsonb)
      into iq_member_projection
      from jsonb_array_elements(iq_members) member(value);
    if iq_members <> iq_sorted_members
       or (select count(*) <> count(distinct value ->> 'consumerId')
             from jsonb_array_elements(iq_members) member(value)) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_INVALID';
    end if;
    iq_expected_members_digest := 'sha256:' || encode(sha256(convert_to(
        ingestion_quality.iq_json_canonical(iq_member_projection), 'UTF8')), 'hex');
    iq_expected_registry_digest := 'sha256:' || encode(sha256(convert_to(
        ingestion_quality.iq_json_canonical(jsonb_build_object(
            'registryVersion','QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0',
            'members',iq_member_projection)), 'UTF8')), 'hex');
    if iq_payload ->> 'membersDigest' <> iq_expected_members_digest
       or iq_payload ->> 'registryDigest' <> iq_expected_registry_digest then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_INVALID';
    end if;

    iq_issued_at := (iq_payload ->> 'issuedAt')::timestamptz;
    iq_expires_at := (iq_payload ->> 'expiresAt')::timestamptz;
    iq_trusted_observed_at := (iq_payload ->> 'trustedObservedAt')::timestamptz;
    iq_legal_hold_checked_at := (iq_payload #>> '{legalHold,checkedAt}')::timestamptz;
    iq_watermarks_checked_at := (iq_payload ->> 'watermarksCheckedAt')::timestamptz;
    if iq_issued_at > iq_statement_at + interval '5 minutes'
       or iq_issued_at >= iq_expires_at
       or iq_trusted_observed_at < iq_issued_at
       or iq_trusted_observed_at >= iq_expires_at
       or iq_legal_hold_checked_at < iq_issued_at
       or iq_legal_hold_checked_at > iq_trusted_observed_at
       or iq_watermarks_checked_at < iq_issued_at
       or iq_watermarks_checked_at > iq_trusted_observed_at
       or iq_trusted_observed_at - iq_legal_hold_checked_at > interval '24 hours'
       or iq_trusted_observed_at - iq_watermarks_checked_at > interval '24 hours'
       or iq_payload #>> '{legalHold,checkedScopeDigest}' <> iq_expected_scope_digest then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_INVALID';
    end if;

    for iq_member in select value from jsonb_array_elements(iq_members) loop
        iq_attestation := iq_member -> 'attestation';
        if not (
             (iq_member ->> 'registryMembership' = 'required-for-snapshot'
              and iq_member ->> 'lifecycleStatus' in ('active','inactive'))
             or (iq_member ->> 'registryMembership' = 'unreleased-reference-holder'
                 and iq_member ->> 'lifecycleStatus' in ('active','inactive','retired'))
             or (iq_member ->> 'registryMembership' = 'planned-never-held-reference'
                 and iq_member ->> 'lifecycleStatus' = 'planned'))
           or (iq_member ->> 'requiredAggregateVersion')::bigint <>
              iq_snapshot.aggregate_version then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_INVALID';
        end if;

        -- A null attestation and negative watermark/ack states are trusted
        -- observations, not malformed authority. They are retained so the
        -- owner can emit the precise production blocker instead of degrading
        -- every negative observation to registry-unavailable.
        if iq_attestation <> 'null'::jsonb then
            iq_attested_at := (iq_attestation ->> 'attestedAt')::timestamptz;
            if iq_attestation ->> 'snapshotId' <>
                  iq_snapshot.snapshot_id::text
               or iq_attestation ->> 'snapshotImmutableHash' <>
                  iq_snapshot.immutable_hash
               or (iq_attestation ->> 'requiredAggregateVersion')::bigint <>
                  iq_snapshot.aggregate_version
               or iq_attestation ->> 'consumerId' <> iq_member ->> 'consumerId'
               or iq_attestation ->> 'registryVersion' <>
                  'QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0'
               or iq_attestation ->> 'registryDigest' <>
                  iq_expected_registry_digest
               or iq_attestation ->> 'scopeDigest' <> iq_expected_scope_digest
               or iq_attested_at < iq_snapshot.evaluated_at
               or iq_attested_at > iq_watermarks_checked_at
               or iq_attested_at > iq_trusted_observed_at
               or (iq_member ->> 'evidenceCopyAck' = 'copied'
                   and iq_attestation ->> 'kind' <> 'evidence-copy')
               or (iq_member ->> 'evidenceCopyAck' = 'not-required'
                   and iq_attestation ->> 'kind' not in (
                     'zero-dependency-no-reference',
                     'decommission-no-reference'))
               or (iq_member ->> 'lifecycleStatus' = 'active'
                   and iq_attestation ->> 'kind' =
                     'decommission-no-reference') then
                raise exception using
                    errcode = 'check_violation',
                    message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_INVALID';
            end if;
        end if;
    end loop;
    if (select count(*) <> count(distinct ingestion_quality.iq_json_canonical(
                 value -> 'attestation'))
          from jsonb_array_elements(iq_members) member(value)
         where value -> 'attestation' <> 'null'::jsonb) then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_INVALID';
    end if;
    iq_attestations_payload := jsonb_build_object('consumers',iq_members);
    iq_attestations_utf8 := convert_to(
        ingestion_quality.iq_json_canonical(iq_attestations_payload), 'UTF8');
    iq_evidence_copy_digest := 'sha256:' || encode(sha256(convert_to(
        ingestion_quality.iq_json_canonical(iq_members), 'UTF8')), 'hex');
    iq_evidence_copy_status := case
      when exists (
        select 1 from jsonb_array_elements(iq_members) member(value)
         where value ->> 'evidenceCopyAck' = 'unavailable') then 'unavailable'
      when exists (
        select 1 from jsonb_array_elements(iq_members) member(value)
         where value ->> 'evidenceCopyAck' = 'missing') then 'missing'
      when exists (
        select 1 from jsonb_array_elements(iq_members) member(value)
         where value ->> 'evidenceCopyAck' = 'copied') then 'copied'
      else 'not-required'
    end;

    insert into ingestion_quality.iq_quality_snapshot_retention_authority_evidence
      (authority_evidence_id, authority_ref, snapshot_id, snapshot_immutable_hash,
       scope_digest, registry_version, registry_digest, members_digest,
       legal_hold_clear, legal_hold_checked_scope_digest, legal_hold_checked_at,
       consumer_attestations_payload_utf8, consumer_attestations_digest,
       watermarks_checked_at, evidence_copy_status, evidence_copy_digest,
       trusted_observed_at, issued_at, expires_at, runtime_evidence_claim,
       verification_status)
    values
      (requested_authority_evidence_id, iq_payload ->> 'evidenceRef',
       iq_snapshot.snapshot_id, iq_snapshot.immutable_hash, iq_expected_scope_digest,
       'QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0', iq_expected_registry_digest,
       iq_expected_members_digest,
       iq_payload #>> '{legalHold,status}' = 'clear', iq_expected_scope_digest,
       iq_legal_hold_checked_at, iq_attestations_utf8,
       encode(sha256(iq_attestations_utf8), 'hex'), iq_watermarks_checked_at,
       iq_evidence_copy_status, iq_evidence_copy_digest, iq_trusted_observed_at,
       iq_issued_at, iq_expires_at, 'production-verified', 'verified')
    on conflict (authority_evidence_id) do nothing;
    get diagnostics iq_inserted = row_count;
    if iq_inserted = 0 then
        select * into strict iq_existing
          from ingestion_quality.iq_quality_snapshot_retention_authority_evidence
         where authority_evidence_id = requested_authority_evidence_id;
        if iq_existing.authority_ref <> iq_payload ->> 'evidenceRef'
           or iq_existing.snapshot_id <> iq_snapshot.snapshot_id
           or iq_existing.snapshot_immutable_hash <> iq_snapshot.immutable_hash
           or iq_existing.scope_digest <> iq_expected_scope_digest
           or iq_existing.registry_digest <> iq_expected_registry_digest
           or iq_existing.members_digest <> iq_expected_members_digest
           or iq_existing.legal_hold_clear <>
              (iq_payload #>> '{legalHold,status}' = 'clear')
           or iq_existing.legal_hold_checked_at <> iq_legal_hold_checked_at
           or iq_existing.consumer_attestations_payload_utf8 <> iq_attestations_utf8
           or iq_existing.watermarks_checked_at <> iq_watermarks_checked_at
           or iq_existing.evidence_copy_status <> iq_evidence_copy_status
           or iq_existing.evidence_copy_digest <> iq_evidence_copy_digest
           or iq_existing.trusted_observed_at <> iq_trusted_observed_at
           or iq_existing.issued_at <> iq_issued_at
           or iq_existing.expires_at <> iq_expires_at then
            raise exception using
                errcode = 'unique_violation',
                message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_ID_CONFLICT';
        end if;
    end if;
    return requested_authority_evidence_id;
end
$$;

revoke all on function
    ingestion_quality.iq_ingest_quality_snapshot_retention_authority(
      uuid, bytea, character)
    from public;

create function ingestion_quality.iq_find_next_due_quality_snapshot_retention()
returns table (
    execution_id uuid,
    snapshot_id uuid,
    source_id varchar,
    snapshot_aggregate_version bigint,
    evaluated_at timestamptz,
    retention_due_at timestamptz,
    snapshot_immutable_hash char(71),
    retention_policy_version varchar,
    retention_schedule_version varchar,
    retention_scope_digest char(71))
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_retention_executor');
    return query
    select coalesce(latest_blocked.execution_id, snapshot.snapshot_id),
           snapshot.snapshot_id,
           snapshot.source_id,
           snapshot.aggregate_version,
           snapshot.evaluated_at,
           snapshot.retention_due_at,
           snapshot.immutable_hash,
           'QUALITY-SNAPSHOT-RETENTION-1.0.0'::varchar,
           snapshot.retention_schedule_version,
           snapshot.retention_scope_digest
      from ingestion_quality.iq_quality_snapshot snapshot
      left join lateral (
        select result.execution_id, result.occurred_at
          from ingestion_quality.iq_quality_snapshot_deletion_result result
         where result.snapshot_id = snapshot.snapshot_id
           and result.snapshot_immutable_hash = snapshot.immutable_hash
           and result.scope_digest = snapshot.retention_scope_digest
           and result.result = 'blocked'
           and not exists (
             select 1
               from ingestion_quality.iq_quality_snapshot_deletion_result terminal
              where terminal.execution_id = result.execution_id
                and terminal.result = 'completed')
         order by result.occurred_at desc,
                  result.aggregate_version desc,
                  result.result_event_id desc
         limit 1
      ) latest_blocked on true
     where snapshot.retention_due_at <= pg_catalog.statement_timestamp()
       and not exists (
         select 1
           from ingestion_quality.iq_quality_snapshot_deletion_result terminal
          where terminal.snapshot_id = snapshot.snapshot_id
            and terminal.snapshot_immutable_hash = snapshot.immutable_hash
            and terminal.scope_digest = snapshot.retention_scope_digest
            and terminal.result = 'completed')
     -- Never-attempted snapshots are served first. Retried snapshots are then
     -- rotated by their latest attempt time so one permanently blocked oldest
     -- snapshot cannot starve later due work. The lateral row still supplies
     -- the same-scope stable execution identity for direct-successor retries.
     order by latest_blocked.occurred_at nulls first,
              snapshot.retention_due_at,
              snapshot.snapshot_id
     limit 1;
end
$$;

revoke all on function
    ingestion_quality.iq_find_next_due_quality_snapshot_retention()
    from public;

create function ingestion_quality.iq_execute_quality_snapshot_retention(
    requested_execution_id uuid,
    requested_result_event_id uuid,
    requested_snapshot_id uuid,
    requested_snapshot_immutable_hash char(71),
    requested_scope_digest char(71),
    requested_authority_evidence_id uuid,
    requested_trace_id char(32))
returns varchar
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_snapshot ingestion_quality.iq_quality_snapshot%rowtype;
    iq_authority
        ingestion_quality.iq_quality_snapshot_retention_authority_evidence%rowtype;
    iq_existing_result
        ingestion_quality.iq_quality_snapshot_deletion_result%rowtype;
    iq_existing_outbox ingestion_quality.iq_batch_quality_outbox%rowtype;
    iq_expected_execution_id uuid;
    iq_has_predecessor boolean := false;
    iq_authority_found boolean := false;
    iq_authority_usable boolean := false;
    iq_attestations jsonb;
    iq_members jsonb := '[]'::jsonb;
    iq_sorted_members jsonb := '[]'::jsonb;
    iq_member_projection jsonb := '[]'::jsonb;
    iq_member jsonb;
    iq_attestation jsonb;
    iq_expected_scope jsonb;
    iq_event_scope jsonb;
    iq_guards jsonb;
    iq_owner_results jsonb;
    iq_blocker_codes jsonb := '[]'::jsonb;
    iq_event jsonb;
    iq_data jsonb;
    iq_replay_authority_id uuid;
    iq_metric_rows jsonb;
    iq_impact_rows jsonb;
    iq_transaction_material jsonb;
    iq_result varchar(16);
    iq_result_version bigint;
    iq_supersedes_result_event_id uuid;
    iq_causation_id uuid;
    iq_transaction_id uuid;
    iq_statement_at timestamptz := pg_catalog.statement_timestamp();
    iq_fallback_at timestamptz;
    iq_decision_at timestamptz;
    iq_occurred_at timestamptz;
    iq_evaluated_text text;
    iq_due_text text;
    iq_occurred_text text;
    iq_backup_due_text text;
    iq_span_id char(16);
    iq_expected_scope_digest char(71);
    iq_expected_members_digest char(71);
    iq_expected_registry_digest char(71);
    iq_empty_registry_digest char(71);
    iq_snapshot_evidence_digest char(71);
    iq_metric_evidence_digest char(71);
    iq_impact_evidence_digest char(71);
    iq_transaction_digest char(71);
    iq_payload_utf8 bytea;
    iq_payload_digest char(64);
    iq_snapshot_count bigint;
    iq_metric_count bigint;
    iq_impact_count bigint;
    iq_deleted_count bigint;
begin
    perform ingestion_quality.iq_require_exclusive_workload(
        'scholarsense_ingestion_quality_retention_executor');
    if requested_execution_id is null
       or requested_result_event_id is null
       or requested_snapshot_id is null
       or requested_authority_evidence_id is null
       or substring(requested_execution_id::text, 15, 1) <> '7'
       or substring(requested_result_event_id::text, 15, 1) <> '7'
       or substring(requested_snapshot_id::text, 15, 1) <> '7'
       or substring(requested_authority_evidence_id::text, 15, 1) <> '7'
       or substring(requested_execution_id::text, 20, 1) not in ('8','9','a','b')
       or substring(requested_result_event_id::text, 20, 1) not in ('8','9','a','b')
       or substring(requested_snapshot_id::text, 20, 1) not in ('8','9','a','b')
       or substring(requested_authority_evidence_id::text, 20, 1)
          not in ('8','9','a','b')
       or requested_result_event_id in (
            requested_execution_id, requested_snapshot_id,
            requested_authority_evidence_id)
       or requested_authority_evidence_id in (
            requested_execution_id, requested_snapshot_id)
       or requested_snapshot_immutable_hash is null
       or requested_snapshot_immutable_hash !~ '^sha256:[0-9a-f]{64}$'
       or requested_scope_digest is null
       or requested_scope_digest !~ '^sha256:[0-9a-f]{64}$'
       or requested_trace_id is null
       or requested_trace_id !~ '^[0-9a-f]{32}$'
       or requested_trace_id ~ '^0{32}$' then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RETENTION_REQUEST_INVALID';
    end if;

    -- Every attempt for an execution is serialized before an event or
    -- snapshot lock. This makes the bounded, non-claiming candidate lookup
    -- safe for concurrent schedulers without inventing a lease or fence.
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        requested_execution_id::text, 23020));
    -- Result-event and authority-evidence IDs inhabit one global identity
    -- namespace. Acquire both locks in lexical UUID order so ingest/execute
    -- races cannot create a cross-table alias and opposing pairs cannot
    -- deadlock each other.
    if requested_result_event_id::text <
       requested_authority_evidence_id::text then
        perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
            requested_result_event_id::text, 23030));
        perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
            requested_authority_evidence_id::text, 23030));
    else
        perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
            requested_authority_evidence_id::text, 23030));
        perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
            requested_result_event_id::text, 23030));
    end if;
    if exists (
         select 1
           from ingestion_quality.iq_quality_snapshot_retention_authority_evidence
          where authority_evidence_id = requested_result_event_id)
       or exists (
         select 1
           from ingestion_quality.iq_quality_snapshot_deletion_result
          where result_event_id = requested_authority_evidence_id)
       or exists (
         select 1
           from ingestion_quality.iq_quality_snapshot_deletion_result
          where authority_evidence_id = requested_result_event_id) then
        raise exception using
            errcode = 'unique_violation',
            message = 'INGESTION_QUALITY_RETENTION_IDENTITY_CONFLICT';
    end if;

    -- Replay is compared only through the canonical public event. The hidden
    -- authority_evidence_id column is deliberately not an idempotency input.
    select * into iq_existing_result
      from ingestion_quality.iq_quality_snapshot_deletion_result
     where result_event_id = requested_result_event_id;
    if found then
        begin
            iq_event := convert_from(iq_existing_result.payload_utf8, 'UTF8')::jsonb;
            iq_data := iq_event -> 'data';
            if iq_data #>> '{guards,consumerRegistry,status}' = 'available' then
                iq_replay_authority_id :=
                    (iq_data #>>
                      '{guards,consumerRegistry,authorityEvidence,authorityEvidenceId}')
                    ::uuid;
            elsif (iq_data ->> 'aggregateVersion')::bigint = 1 then
                iq_replay_authority_id := (iq_data ->> 'causationId')::uuid;
            else
                iq_replay_authority_id := null;
            end if;
        exception when others then
            raise exception using
                errcode = 'unique_violation',
                message = 'INGESTION_QUALITY_DELETION_RESULT_IDEMPOTENCY_CONFLICT';
        end;
        if iq_existing_result.execution_id <> requested_execution_id
           or iq_existing_result.snapshot_id <> requested_snapshot_id
           or iq_existing_result.snapshot_immutable_hash <>
              requested_snapshot_immutable_hash
           or iq_existing_result.scope_digest <> requested_scope_digest
           or iq_event ->> 'id' <> requested_result_event_id::text
           or iq_event ->> 'type' <>
              'scholarsense.ingestion-quality.quality-snapshot.deletion-result.v1'
           or iq_data ->> 'resultContractVersion' <>
              'QUALITY-SNAPSHOT-DELETION-RESULT-1.1.0'
           or iq_data ->> 'executionId' <> requested_execution_id::text
           or iq_data #>> '{scope,snapshotId}' <> requested_snapshot_id::text
           or iq_data #>> '{scope,snapshotImmutableHash}' <>
              requested_snapshot_immutable_hash
           or iq_data #>> '{scope,scopeDigest}' <> requested_scope_digest
           or iq_data ->> 'traceId' <> requested_trace_id
           or (iq_data #>> '{guards,consumerRegistry,status}' = 'available'
               and iq_replay_authority_id is distinct from
                   requested_authority_evidence_id)
           or ((iq_data ->> 'aggregateVersion')::bigint = 1
               and iq_data #>> '{guards,consumerRegistry,status}' = 'unavailable'
               and iq_replay_authority_id is distinct from
                   requested_authority_evidence_id)
           or iq_existing_result.payload_digest <>
              encode(sha256(iq_existing_result.payload_utf8), 'hex') then
            raise exception using
                errcode = 'unique_violation',
                message = 'INGESTION_QUALITY_DELETION_RESULT_IDEMPOTENCY_CONFLICT';
        end if;
        select * into iq_existing_outbox
          from ingestion_quality.iq_batch_quality_outbox
         where event_id = requested_result_event_id;
        if not found
           or iq_existing_outbox.aggregate_id <> requested_execution_id
           or iq_existing_outbox.aggregate_version <>
              iq_existing_result.aggregate_version
           or iq_existing_outbox.event_type <>
              'scholarsense.ingestion-quality.quality-snapshot.deletion-result.v1'
           or iq_existing_outbox.schema_version <>
              'QUALITY-SNAPSHOT-DELETION-RESULT-1.1.0'
           or iq_existing_outbox.payload_utf8 <>
              iq_existing_result.payload_utf8
           or iq_existing_outbox.payload_digest <>
              iq_existing_result.payload_digest then
            raise exception using
                errcode = 'unique_violation',
                message = 'INGESTION_QUALITY_DELETION_RESULT_IDEMPOTENCY_CONFLICT';
        end if;
        return iq_existing_result.result;
    end if;
    if exists (
        select 1 from ingestion_quality.iq_batch_quality_outbox
         where event_id = requested_result_event_id) then
        raise exception using
            errcode = 'unique_violation',
            message = 'INGESTION_QUALITY_DELETION_RESULT_IDEMPOTENCY_CONFLICT';
    end if;

    select * into iq_existing_result
      from ingestion_quality.iq_quality_snapshot_deletion_result
     where execution_id = requested_execution_id
     order by aggregate_version desc
     limit 1
     for update;
    iq_has_predecessor := found;
    if iq_has_predecessor then
        if iq_existing_result.snapshot_id <> requested_snapshot_id
           or iq_existing_result.snapshot_immutable_hash <>
              requested_snapshot_immutable_hash
           or iq_existing_result.scope_digest <> requested_scope_digest then
            raise exception using
                errcode = 'unique_violation',
                message = 'INGESTION_QUALITY_DELETION_RESULT_AGGREGATE_CONFLICT';
        end if;
        if iq_existing_result.result = 'completed' then
            -- A concurrent loser observes the completed terminal and creates
            -- no second result or outbox event. Its unconsumed authority row
            -- remains eligible for the bounded expiry cleanup.
            return 'completed';
        elsif iq_existing_result.result <> 'blocked' then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_DELETION_RESULT_TERMINAL';
        end if;
        iq_supersedes_result_event_id := iq_existing_result.result_event_id;
        iq_result_version := iq_existing_result.aggregate_version + 1;
        select blocked.execution_id into iq_expected_execution_id
          from ingestion_quality.iq_quality_snapshot_deletion_result blocked
         where blocked.snapshot_id = requested_snapshot_id
           and blocked.snapshot_immutable_hash = requested_snapshot_immutable_hash
           and blocked.scope_digest = requested_scope_digest
           and blocked.result = 'blocked'
           and not exists (
             select 1
               from ingestion_quality.iq_quality_snapshot_deletion_result terminal
              where terminal.execution_id = blocked.execution_id
                and terminal.result = 'completed')
         order by blocked.occurred_at desc,
                  blocked.aggregate_version desc,
                  blocked.result_event_id desc
         limit 1;
        if iq_expected_execution_id is distinct from requested_execution_id then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_RETENTION_EXECUTION_ID_INVALID';
        end if;
    else
        iq_supersedes_result_event_id := null;
        iq_result_version := 1;
        if requested_execution_id <> requested_snapshot_id then
            raise exception using
                errcode = 'check_violation',
                message = 'INGESTION_QUALITY_RETENTION_EXECUTION_ID_INVALID';
        end if;
    end if;

    if exists (
         select 1
           from ingestion_quality.iq_quality_snapshot_deletion_result lineage
          where lineage.execution_id = requested_execution_id
            and lineage.result_event_id = requested_authority_evidence_id)
       or exists (
         select 1
           from ingestion_quality.iq_quality_snapshot_deletion_result lineage
          where lineage.execution_id = requested_execution_id
            and lineage.result_event_id = requested_result_event_id) then
        raise exception using
            errcode = 'unique_violation',
            message = 'INGESTION_QUALITY_RETENTION_IDENTITY_CONFLICT';
    end if;

    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(
        requested_snapshot_id::text, 23022));
    select * into iq_snapshot
      from ingestion_quality.iq_quality_snapshot
     where snapshot_id = requested_snapshot_id
     for update;
    if not found then
        raise exception using
            errcode = 'serialization_failure',
            message = 'INGESTION_QUALITY_RETENTION_SNAPSHOT_RACE';
    end if;
    -- statement_timestamp() freezes before lock waits; all expiry and owner
    -- decision timestamps are therefore sampled again after serialization.
    iq_statement_at := pg_catalog.clock_timestamp();
    iq_evaluated_text := case
      when date_trunc('second', iq_snapshot.evaluated_at) = iq_snapshot.evaluated_at
        then to_char(iq_snapshot.evaluated_at at time zone 'UTC',
                     'YYYY-MM-DD"T"HH24:MI:SS"Z"')
      else ingestion_quality.iq_canonical_instant(iq_snapshot.evaluated_at)
    end;
    iq_due_text := case
      when date_trunc('second', iq_snapshot.retention_due_at) =
           iq_snapshot.retention_due_at
        then to_char(iq_snapshot.retention_due_at at time zone 'UTC',
                     'YYYY-MM-DD"T"HH24:MI:SS"Z"')
      else ingestion_quality.iq_canonical_instant(iq_snapshot.retention_due_at)
    end;
    iq_expected_scope := jsonb_build_object(
        'objectType','QualitySnapshot',
        'snapshotId',iq_snapshot.snapshot_id::text,
        'sourceId',iq_snapshot.source_id,
        'snapshotAggregateVersion',iq_snapshot.aggregate_version,
        'evaluatedAt',iq_evaluated_text,
        'retentionDueAt',iq_due_text,
        'snapshotImmutableHash',iq_snapshot.immutable_hash::text,
        'retentionPolicyVersion','QUALITY-SNAPSHOT-RETENTION-1.0.0',
        'retentionScheduleVersion',iq_snapshot.retention_schedule_version);
    iq_expected_scope_digest := 'sha256:' || encode(sha256(convert_to(
        ingestion_quality.iq_json_canonical(iq_expected_scope), 'UTF8')), 'hex');
    if iq_snapshot.immutable_hash <> requested_snapshot_immutable_hash
       or iq_snapshot.retention_scope_digest <> requested_scope_digest
       or iq_expected_scope_digest <> requested_scope_digest then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_RETENTION_SCOPE_INVALID';
    end if;
    iq_event_scope := jsonb_build_object(
        'objectType','QualitySnapshot',
        'snapshotId',iq_snapshot.snapshot_id::text,
        'sourceId',iq_snapshot.source_id,
        'snapshotAggregateVersion',iq_snapshot.aggregate_version,
        'evaluatedAt',iq_evaluated_text,
        'retentionDueAt',iq_due_text,
        'snapshotImmutableHash',iq_snapshot.immutable_hash::text,
        'scopeDigest',requested_scope_digest::text);

    select * into iq_authority
      from ingestion_quality.iq_quality_snapshot_retention_authority_evidence
     where authority_evidence_id = requested_authority_evidence_id
     for update;
    iq_authority_found := found;
    -- The authority row can itself be contended, so destructive eligibility
    -- and expiry are sampled only after both owner locks have been acquired.
    iq_statement_at := pg_catalog.clock_timestamp();
    iq_fallback_at := greatest(iq_statement_at, iq_snapshot.evaluated_at);
    if iq_has_predecessor then
        iq_fallback_at := greatest(
            iq_fallback_at, iq_existing_result.occurred_at);
    end if;
    iq_authority_usable := iq_authority_found;
    if iq_authority_found then
        begin
            iq_attestations := convert_from(
                iq_authority.consumer_attestations_payload_utf8, 'UTF8')::jsonb;
            if ingestion_quality.iq_json_exact_object(
                 iq_attestations, array['consumers']) is not true
               or jsonb_typeof(iq_attestations -> 'consumers') <> 'array'
               or jsonb_array_length(iq_attestations -> 'consumers') > 32
               or iq_authority.consumer_attestations_digest <>
                  encode(sha256(iq_authority.consumer_attestations_payload_utf8),
                         'hex') then
                iq_authority_usable := false;
            end if;
            iq_members := coalesce(iq_attestations -> 'consumers', '[]'::jsonb);
            select coalesce(jsonb_agg(value order by
                       (value ->> 'consumerId') collate "C"), '[]'::jsonb)
              into iq_sorted_members
              from jsonb_array_elements(iq_members) member(value);
            select coalesce(jsonb_agg(jsonb_build_object(
                       'consumerId',value -> 'consumerId',
                       'registryMembership',value -> 'registryMembership',
                       'lifecycleStatus',value -> 'lifecycleStatus')
                       order by (value ->> 'consumerId') collate "C"), '[]'::jsonb)
              into iq_member_projection
              from jsonb_array_elements(iq_members) member(value);
            iq_expected_members_digest := 'sha256:' || encode(sha256(convert_to(
                ingestion_quality.iq_json_canonical(iq_member_projection),
                'UTF8')), 'hex');
            iq_expected_registry_digest := 'sha256:' || encode(sha256(convert_to(
                ingestion_quality.iq_json_canonical(jsonb_build_object(
                  'registryVersion','QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0',
                  'members',iq_member_projection)), 'UTF8')), 'hex');
            if iq_members <> iq_sorted_members
               or (select count(*) <> count(distinct value ->> 'consumerId')
                     from jsonb_array_elements(iq_members) member(value))
               or iq_authority.authority_ref <>
                  'consumer-registry-authority://production/quality-snapshot/' ||
                  requested_authority_evidence_id::text
               or iq_authority.snapshot_id <> requested_snapshot_id
               or iq_authority.snapshot_immutable_hash <>
                  requested_snapshot_immutable_hash
               or iq_authority.scope_digest <> requested_scope_digest
               or iq_authority.registry_version <>
                  'QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0'
               or iq_authority.registry_digest <> iq_expected_registry_digest
               or iq_authority.members_digest <> iq_expected_members_digest
               or iq_authority.legal_hold_checked_scope_digest <>
                  requested_scope_digest
               or iq_authority.runtime_evidence_claim <> 'production-verified'
               or iq_authority.verification_status <> 'verified'
               or iq_authority.issued_at > iq_statement_at + interval '5 minutes'
               or iq_authority.issued_at >= iq_authority.expires_at
               or iq_statement_at >= iq_authority.expires_at
               or iq_authority.trusted_observed_at < iq_authority.issued_at
               or iq_authority.trusted_observed_at >= iq_authority.expires_at
               or iq_authority.trusted_observed_at < iq_snapshot.evaluated_at
               or iq_authority.legal_hold_checked_at < iq_authority.issued_at
               or iq_authority.legal_hold_checked_at >
                  iq_authority.trusted_observed_at
               or iq_authority.legal_hold_checked_at < iq_snapshot.evaluated_at
               or iq_authority.watermarks_checked_at < iq_authority.issued_at
               or iq_authority.watermarks_checked_at >
                  iq_authority.trusted_observed_at
               or iq_authority.watermarks_checked_at < iq_snapshot.evaluated_at
               or (iq_authority.trusted_observed_at >=
                     iq_snapshot.retention_due_at
                   and (iq_authority.legal_hold_checked_at <
                          iq_snapshot.retention_due_at
                        or iq_authority.watermarks_checked_at <
                          iq_snapshot.retention_due_at))
               or iq_authority.trusted_observed_at -
                  iq_authority.legal_hold_checked_at > interval '24 hours'
               or iq_authority.trusted_observed_at -
                  iq_authority.watermarks_checked_at > interval '24 hours'
               or iq_authority.consumed_at is not null
               or iq_authority.execution_id is not null
               or (iq_has_predecessor and (
                    iq_authority.trusted_observed_at < iq_existing_result.occurred_at
                    or iq_authority.legal_hold_checked_at <
                       iq_existing_result.occurred_at
                    or iq_authority.watermarks_checked_at <
                       iq_existing_result.occurred_at)) then
                iq_authority_usable := false;
            end if;
            if iq_authority.evidence_copy_digest <>
                 'sha256:' || encode(sha256(convert_to(
                   ingestion_quality.iq_json_canonical(iq_members), 'UTF8')), 'hex')
               or iq_authority.evidence_copy_status <> (case
                  when exists (
                    select 1 from jsonb_array_elements(iq_members) member(value)
                     where value ->> 'evidenceCopyAck' = 'unavailable')
                    then 'unavailable'
                  when exists (
                    select 1 from jsonb_array_elements(iq_members) member(value)
                     where value ->> 'evidenceCopyAck' = 'missing')
                    then 'missing'
                  when exists (
                    select 1 from jsonb_array_elements(iq_members) member(value)
                     where value ->> 'evidenceCopyAck' = 'copied')
                    then 'copied'
                  else 'not-required'
                end) then
                iq_authority_usable := false;
            end if;
            for iq_member in select value from jsonb_array_elements(iq_members) loop
                iq_attestation := iq_member -> 'attestation';
                if ingestion_quality.iq_json_exact_object(iq_member, array[
                     'consumerId','registryMembership','lifecycleStatus',
                     'watermarkStatus','requiredAggregateVersion',
                     'confirmedAggregateVersion','transportAck','inboxAck',
                     'evidenceCopyAck','attestation']) is not true
                   or jsonb_typeof(iq_member -> 'consumerId') <> 'string'
                   or iq_member ->> 'consumerId' !~ '^[a-z][a-z0-9-]{2,79}$'
                   or iq_member ->> 'registryMembership' not in (
                     'required-for-snapshot','unreleased-reference-holder',
                     'planned-never-held-reference')
                   or iq_member ->> 'lifecycleStatus' not in (
                     'active','inactive','planned','retired')
                   or iq_member ->> 'watermarkStatus' not in (
                     'confirmed','missing','unknown')
                   or not ingestion_quality.iq_json_safe_nonnegative_integer(
                        iq_member -> 'requiredAggregateVersion')
                   or (iq_member ->> 'requiredAggregateVersion')::numeric < 1
                   or not (
                     iq_member -> 'confirmedAggregateVersion' = 'null'::jsonb
                     or ingestion_quality.iq_json_safe_nonnegative_integer(
                          iq_member -> 'confirmedAggregateVersion'))
                   or iq_member ->> 'transportAck' not in ('acked','missing')
                   or iq_member ->> 'inboxAck' not in ('acked','missing')
                   or iq_member ->> 'evidenceCopyAck' not in (
                     'copied','not-required','missing','unavailable')
                   or not (
                     (iq_member ->> 'registryMembership' = 'required-for-snapshot'
                      and iq_member ->> 'lifecycleStatus' in ('active','inactive'))
                     or (iq_member ->> 'registryMembership' =
                           'unreleased-reference-holder'
                         and iq_member ->> 'lifecycleStatus' in (
                           'active','inactive','retired'))
                     or (iq_member ->> 'registryMembership' =
                           'planned-never-held-reference'
                         and iq_member ->> 'lifecycleStatus' = 'planned'))
                   or (iq_member ->> 'requiredAggregateVersion')::bigint <>
                      iq_snapshot.aggregate_version then
                    iq_authority_usable := false;
                end if;

                if iq_attestation <> 'null'::jsonb then
                    if ingestion_quality.iq_json_exact_object(
                         iq_attestation, array[
                         'kind','snapshotId','snapshotImmutableHash',
                         'requiredAggregateVersion','consumerId','registryVersion',
                         'registryDigest','scopeDigest','attestedAt']) is not true
                       or iq_attestation ->> 'kind' not in (
                         'evidence-copy','zero-dependency-no-reference',
                         'decommission-no-reference')
                       or not ingestion_quality.iq_json_uuid_v7(
                            iq_attestation -> 'snapshotId')
                       or not ingestion_quality.iq_json_digest(
                            iq_attestation -> 'snapshotImmutableHash')
                       or not ingestion_quality.iq_json_safe_nonnegative_integer(
                            iq_attestation -> 'requiredAggregateVersion')
                       or jsonb_typeof(iq_attestation -> 'consumerId') <> 'string'
                       or iq_attestation ->> 'snapshotId' <>
                          requested_snapshot_id::text
                       or iq_attestation ->> 'snapshotImmutableHash' <>
                          requested_snapshot_immutable_hash
                       or (iq_attestation ->> 'requiredAggregateVersion')::bigint <>
                          iq_snapshot.aggregate_version
                       or iq_attestation ->> 'consumerId' <>
                          iq_member ->> 'consumerId'
                       or iq_attestation ->> 'registryVersion' <>
                          'QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0'
                       or iq_attestation ->> 'registryDigest' <>
                          iq_expected_registry_digest
                       or iq_attestation ->> 'scopeDigest' <>
                          requested_scope_digest
                       or not ingestion_quality.iq_json_timestamp(
                            iq_attestation -> 'attestedAt')
                       or (iq_attestation ->> 'attestedAt')::timestamptz <
                          iq_snapshot.evaluated_at
                       or (iq_attestation ->> 'attestedAt')::timestamptz >
                          iq_authority.watermarks_checked_at
                       or (iq_attestation ->> 'attestedAt')::timestamptz >
                          iq_authority.trusted_observed_at
                       or (iq_member ->> 'evidenceCopyAck' = 'copied'
                           and iq_attestation ->> 'kind' <> 'evidence-copy')
                       or (iq_member ->> 'evidenceCopyAck' = 'not-required'
                           and iq_attestation ->> 'kind' not in (
                             'zero-dependency-no-reference',
                             'decommission-no-reference'))
                       or (iq_member ->> 'lifecycleStatus' = 'active'
                           and iq_attestation ->> 'kind' =
                             'decommission-no-reference') then
                        iq_authority_usable := false;
                    end if;
                end if;

                -- Eligibility is intentionally evaluated after structural
                -- authority validation. Trusted negative evidence remains an
                -- available registry guard and maps to Task-0 blocker codes.
                if iq_member ->> 'registryMembership' <>
                     'planned-never-held-reference'
                   and not (
                     iq_member ->> 'registryMembership' =
                       'unreleased-reference-holder'
                     and iq_attestation <> 'null'::jsonb
                     and iq_attestation ->> 'kind' =
                       'decommission-no-reference') then
                    if iq_member ->> 'watermarkStatus' = 'missing' then
                        iq_blocker_codes := iq_blocker_codes ||
                            jsonb_build_array('CONSUMER_WATERMARK_MISSING');
                    elsif iq_member ->> 'watermarkStatus' = 'unknown' then
                        iq_blocker_codes := iq_blocker_codes ||
                            jsonb_build_array('CONSUMER_WATERMARK_UNKNOWN');
                    elsif iq_member -> 'confirmedAggregateVersion' =
                          'null'::jsonb then
                        iq_blocker_codes := iq_blocker_codes ||
                            jsonb_build_array('WATERMARK_DEPENDENCY_UNAVAILABLE');
                    elsif (iq_member ->> 'confirmedAggregateVersion')::bigint <
                          iq_snapshot.aggregate_version then
                        iq_blocker_codes := iq_blocker_codes ||
                            jsonb_build_array('CONSUMER_WATERMARK_BEHIND');
                    end if;
                    if iq_member ->> 'transportAck' <> 'acked'
                       or iq_member ->> 'inboxAck' <> 'acked' then
                        iq_blocker_codes := iq_blocker_codes ||
                            jsonb_build_array('WATERMARK_DEPENDENCY_UNAVAILABLE');
                    end if;
                    if not (
                         (iq_member ->> 'evidenceCopyAck' = 'copied'
                          and iq_attestation <> 'null'::jsonb
                          and iq_attestation ->> 'kind' = 'evidence-copy')
                         or (iq_member ->> 'evidenceCopyAck' = 'not-required'
                             and iq_attestation <> 'null'::jsonb
                             and iq_attestation ->> 'kind' in (
                               'zero-dependency-no-reference',
                               'decommission-no-reference'))) then
                        iq_blocker_codes := iq_blocker_codes ||
                            jsonb_build_array('EVIDENCE_COPY_ACK_MISSING');
                    end if;
                end if;
            end loop;
            if (select count(*) <> count(distinct
                         ingestion_quality.iq_json_canonical(value -> 'attestation'))
                  from jsonb_array_elements(iq_members) member(value)
                 where value -> 'attestation' <> 'null'::jsonb) then
                iq_authority_usable := false;
            end if;
        exception when others then
            iq_authority_usable := false;
        end;
    end if;

    iq_empty_registry_digest := 'sha256:' || encode(sha256(convert_to(
        ingestion_quality.iq_json_canonical(jsonb_build_object(
          'registryVersion','QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0',
          'members','[]'::jsonb)), 'UTF8')), 'hex');
    if iq_authority_usable then
        iq_decision_at := greatest(
            iq_statement_at, iq_authority.trusted_observed_at);
        if iq_authority.trusted_observed_at < iq_snapshot.retention_due_at then
            iq_blocker_codes := iq_blocker_codes ||
                jsonb_build_array('RETENTION_NOT_DUE');
        end if;
        if not iq_authority.legal_hold_clear then
            iq_blocker_codes := iq_blocker_codes ||
                jsonb_build_array('LEGAL_HOLD_MATCHED');
        end if;
    else
        iq_members := '[]'::jsonb;
        iq_member_projection := '[]'::jsonb;
        iq_decision_at := iq_fallback_at;
        iq_blocker_codes := jsonb_build_array('CONSUMER_REGISTRY_UNAVAILABLE');
        if iq_fallback_at < iq_snapshot.retention_due_at then
            iq_blocker_codes := iq_blocker_codes ||
                jsonb_build_array('RETENTION_NOT_DUE');
        end if;
        if iq_snapshot.legal_hold then
            iq_blocker_codes := iq_blocker_codes ||
                jsonb_build_array('LEGAL_HOLD_MATCHED');
        end if;
    end if;
    select coalesce(jsonb_agg(code order by blocker_text collate "C"), '[]'::jsonb)
      into iq_blocker_codes
      from (
        select distinct value as code, value #>> '{}' as blocker_text
          from jsonb_array_elements(iq_blocker_codes) blocker(value)
      ) distinct_blockers;
    iq_occurred_at := iq_fallback_at;
    if iq_authority_usable then
        iq_occurred_at := greatest(
            iq_occurred_at, iq_authority.trusted_observed_at);
    end if;
    if iq_has_predecessor then
        iq_occurred_at := greatest(iq_occurred_at, iq_existing_result.occurred_at);
    end if;
    iq_occurred_text := ingestion_quality.iq_canonical_instant(iq_occurred_at);
    iq_backup_due_text := ingestion_quality.iq_canonical_instant(
        iq_occurred_at + interval '35 days');
    iq_span_id := substring(encode(sha256(convert_to(
        'quality-snapshot-retention|' || requested_trace_id::text, 'UTF8')),
        'hex') from 1 for 16);
    if iq_span_id ~ '^0{16}$' then
        iq_span_id := '0000000000000001';
    end if;

    if iq_authority_usable then
        iq_guards := jsonb_build_object(
            'trustedTime',jsonb_build_object(
                'status','available',
                'observedAt',ingestion_quality.iq_canonical_instant(
                    iq_authority.trusted_observed_at)),
            'legalHold',jsonb_build_object(
                'status',case when iq_authority.legal_hold_clear
                              then 'clear' else 'matched' end,
                'checkedScopeDigest',requested_scope_digest::text,
                'matchedCount',case when iq_authority.legal_hold_clear then 0 else 1 end,
                'matchedScopeDigests',case when iq_authority.legal_hold_clear
                     then '[]'::jsonb
                     else jsonb_build_array(requested_scope_digest::text) end,
                'checkedAt',ingestion_quality.iq_canonical_instant(
                    iq_authority.legal_hold_checked_at)),
            'consumerRegistry',jsonb_build_object(
                'status','available',
                'registryVersion','QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0',
                'registryDigest',iq_authority.registry_digest::text,
                'members',iq_member_projection,
                'checkedAt',ingestion_quality.iq_canonical_instant(
                    iq_authority.watermarks_checked_at),
                'authorityEvidence',jsonb_build_object(
                    'authorityEvidenceId',requested_authority_evidence_id::text,
                    'provider','consumer-registry-authority',
                    'evidenceRef',iq_authority.authority_ref,
                    'scopeDigest',requested_scope_digest::text,
                    'registryVersion','QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0',
                    'registryDigest',iq_authority.registry_digest::text,
                    'membersDigest',iq_authority.members_digest::text,
                    'checkedAt',ingestion_quality.iq_canonical_instant(
                        iq_authority.watermarks_checked_at),
                    'verificationStatus','verified',
                    'runtimeEvidenceClaim','production-verified')),
            'consumerWatermarksCheckedAt',
                ingestion_quality.iq_canonical_instant(
                    iq_authority.watermarks_checked_at),
            'consumerWatermarks',iq_members);
    else
        iq_guards := jsonb_build_object(
            'trustedTime',jsonb_build_object(
                'status','available','observedAt',
                ingestion_quality.iq_canonical_instant(iq_fallback_at)),
            'legalHold',jsonb_build_object(
                'status',case when iq_snapshot.legal_hold
                              then 'matched' else 'clear' end,
                'checkedScopeDigest',requested_scope_digest::text,
                'matchedCount',case when iq_snapshot.legal_hold then 1 else 0 end,
                'matchedScopeDigests',case when iq_snapshot.legal_hold
                     then jsonb_build_array(requested_scope_digest::text)
                     else '[]'::jsonb end,
                'checkedAt',ingestion_quality.iq_canonical_instant(iq_fallback_at)),
            'consumerRegistry',jsonb_build_object(
                'status','unavailable',
                'registryVersion','QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0',
                'registryDigest',iq_empty_registry_digest::text,
                'members','[]'::jsonb,
                'checkedAt',null,
                'authorityEvidence',null),
            'consumerWatermarksCheckedAt',null,
            'consumerWatermarks','[]'::jsonb);
    end if;

    if jsonb_array_length(iq_blocker_codes) > 0 then
        iq_result := 'blocked';
        iq_owner_results := jsonb_build_object(
            'onlineSnapshot',jsonb_build_object(
                'status','not-attempted','selectedCount',0,'deletedCount',0,
                'remainingCount',0,'evidenceDigest',null,'errorCode',null,
                'transactionId',null,'transactionEvidenceDigest',null),
            'onlineMetrics',jsonb_build_object(
                'status','not-attempted','selectedCount',0,'deletedCount',0,
                'remainingCount',0,'evidenceDigest',null,'errorCode',null,
                'transactionId',null,'transactionEvidenceDigest',null),
            'readModels',jsonb_build_object(
                'status','not-attempted','selectedCount',0,'deletedCount',0,
                'remainingCount',0,'evidenceDigest',null,'errorCode',null,
                'transactionId',null,'transactionEvidenceDigest',null),
            'indexes',jsonb_build_object(
                'status','not-attempted','selectedCount',0,'deletedCount',0,
                'remainingCount',0,'evidenceDigest',null,'errorCode',null,
                'transactionId',null,'transactionEvidenceDigest',null),
            'caches',jsonb_build_object(
                'status','not-attempted','selectedCount',0,'deletedCount',0,
                'remainingCount',0,'evidenceDigest',null,'errorCode',null,
                'transactionId',null,'transactionEvidenceDigest',null),
            'objects',jsonb_build_object(
                'status','not-attempted','selectedCount',0,'deletedCount',0,
                'remainingCount',0,'evidenceDigest',null,'errorCode',null,
                'transactionId',null,'transactionEvidenceDigest',null));
    else
        iq_result := 'completed';
        select count(*) into iq_snapshot_count
          from ingestion_quality.iq_quality_snapshot
         where snapshot_id = requested_snapshot_id;
        select count(*), coalesce(jsonb_agg(to_jsonb(metric)
                   order by metric.metric_ordinal), '[]'::jsonb)
          into iq_metric_count, iq_metric_rows
          from ingestion_quality.iq_quality_snapshot_metric metric
         where metric.snapshot_id = requested_snapshot_id;
        select count(*), coalesce(jsonb_agg(to_jsonb(impact)
                   order by impact.scope_ordinal), '[]'::jsonb)
          into iq_impact_count, iq_impact_rows
          from ingestion_quality.iq_quality_snapshot_impact_scope impact
         where impact.snapshot_id = requested_snapshot_id;
        if iq_snapshot_count <> 1 or iq_metric_count < 1 then
            raise exception using
                errcode = 'serialization_failure',
                message = 'INGESTION_QUALITY_RETENTION_SNAPSHOT_RACE';
        end if;
        iq_snapshot_evidence_digest := 'sha256:' || encode(sha256(convert_to(
            ingestion_quality.iq_json_canonical(to_jsonb(iq_snapshot)), 'UTF8')),
            'hex');
        iq_metric_evidence_digest := 'sha256:' || encode(sha256(convert_to(
            ingestion_quality.iq_json_canonical(iq_metric_rows), 'UTF8')), 'hex');
        iq_impact_evidence_digest := 'sha256:' || encode(sha256(convert_to(
            ingestion_quality.iq_json_canonical(iq_impact_rows), 'UTF8')), 'hex');
        loop
            iq_transaction_id := pg_catalog.uuidv7();
            exit when iq_transaction_id not in (
                    requested_execution_id, requested_result_event_id,
                    requested_snapshot_id, requested_authority_evidence_id)
              and not exists (
                select 1
                  from ingestion_quality.iq_quality_snapshot_deletion_result
                 where result_event_id = iq_transaction_id);
        end loop;
        iq_transaction_material := jsonb_build_object(
            'executionId',requested_execution_id::text,
            'scopeDigest',requested_scope_digest::text,
            'result','completed',
            'deletionCommittedAt',iq_occurred_text,
            'transactionId',iq_transaction_id::text,
            'onlineSnapshot',jsonb_build_object(
                'status','deleted','selectedCount',1,'deletedCount',1,
                'remainingCount',0,
                'evidenceDigest',iq_snapshot_evidence_digest::text,
                'errorCode',null),
            'onlineMetrics',jsonb_build_object(
                'status','deleted','selectedCount',iq_metric_count,
                'deletedCount',iq_metric_count,'remainingCount',0,
                'evidenceDigest',iq_metric_evidence_digest::text,
                'errorCode',null),
            'readModels',jsonb_build_object(
                'status',case when iq_impact_count = 0
                              then 'already-absent' else 'deleted' end,
                'selectedCount',iq_impact_count,'deletedCount',iq_impact_count,
                'remainingCount',0,
                'evidenceDigest',iq_impact_evidence_digest::text,
                'errorCode',null));
        iq_transaction_digest := 'sha256:' || encode(sha256(convert_to(
            ingestion_quality.iq_json_canonical(iq_transaction_material),
            'UTF8')), 'hex');
        iq_owner_results := jsonb_build_object(
            'onlineSnapshot',jsonb_build_object(
                'status','deleted','selectedCount',1,'deletedCount',1,
                'remainingCount',0,
                'evidenceDigest',iq_snapshot_evidence_digest::text,
                'errorCode',null,'transactionId',iq_transaction_id::text,
                'transactionEvidenceDigest',iq_transaction_digest::text),
            'onlineMetrics',jsonb_build_object(
                'status','deleted','selectedCount',iq_metric_count,
                'deletedCount',iq_metric_count,'remainingCount',0,
                'evidenceDigest',iq_metric_evidence_digest::text,
                'errorCode',null,'transactionId',iq_transaction_id::text,
                'transactionEvidenceDigest',iq_transaction_digest::text),
            'readModels',jsonb_build_object(
                'status',case when iq_impact_count = 0
                              then 'already-absent' else 'deleted' end,
                'selectedCount',iq_impact_count,'deletedCount',iq_impact_count,
                'remainingCount',0,
                'evidenceDigest',iq_impact_evidence_digest::text,
                'errorCode',null,'transactionId',iq_transaction_id::text,
                'transactionEvidenceDigest',iq_transaction_digest::text),
            'indexes',jsonb_build_object(
                'status','not-applicable','selectedCount',0,'deletedCount',0,
                'remainingCount',0,'evidenceDigest',null,'errorCode',null,
                'transactionId',null,'transactionEvidenceDigest',null),
            'caches',jsonb_build_object(
                'status','not-applicable','selectedCount',0,'deletedCount',0,
                'remainingCount',0,'evidenceDigest',null,'errorCode',null,
                'transactionId',null,'transactionEvidenceDigest',null),
            'objects',jsonb_build_object(
                'status','not-applicable','selectedCount',0,'deletedCount',0,
                'remainingCount',0,'evidenceDigest',null,'errorCode',null,
                'transactionId',null,'transactionEvidenceDigest',null));
    end if;

    iq_causation_id := coalesce(
        iq_supersedes_result_event_id, requested_authority_evidence_id);
    iq_data := jsonb_build_object(
        'resultContractVersion','QUALITY-SNAPSHOT-DELETION-RESULT-1.1.0',
        'contractVersion','PIC-1.0.0',
        'correlationId',requested_execution_id::text,
        'causationId',iq_causation_id::text,
        'eventId',requested_result_event_id::text,
        'aggregateType','quality-snapshot-deletion-execution',
        'aggregateId',requested_execution_id::text,
        'aggregateVersion',iq_result_version,
        'executionId',requested_execution_id::text,
        'supersedesResultId',case when iq_supersedes_result_event_id is null
             then null else iq_supersedes_result_event_id::text end,
        'producer','ingestion-quality-retention-executor',
        'occurredAt',iq_occurred_text,
        'traceId',requested_trace_id::text,
        'result',iq_result,
        'retentionPolicyVersion','QUALITY-SNAPSHOT-RETENTION-1.0.0',
        'retentionScheduleVersion','RS-1.0.0',
        'scope',iq_event_scope,
        'guards',iq_guards,
        'ownerLocalResults',iq_owner_results,
        'deletionCommittedAt',case when iq_result = 'completed'
             then iq_occurred_text else null end,
        'blockerCodes',iq_blocker_codes,
        'failureCodes','[]'::jsonb,
        'backup',jsonb_build_object(
            'policyVersion','DRP-1.0.0',
            'maximumRetentionDays',35,
            'backupExpiryDueAt',case when iq_result = 'completed'
                 then iq_backup_due_text else null end,
            'physicalDeletionClaim','none'),
        'auditHandoff',jsonb_build_object(
            'targetOwner','audit-operations',
            'inputKind','owner-local-deletion-result',
            'finalReceiptOwner','audit-operations',
            'ownerResultIsFinalReceipt',false,
            'conformanceReceiptSatisfiesProduction',false),
        'canonicalizationProfile','SCHOLARSENSE-CANONICAL-JSON-1.0.0',
        'runtimeEvidenceClaim','none');
    iq_event := jsonb_build_object(
        'specversion','1.0',
        'id',requested_result_event_id::text,
        'source','urn:scholarsense:ingestion-quality',
        'type','scholarsense.ingestion-quality.quality-snapshot.deletion-result.v1',
        'subject','quality-snapshot/' || requested_snapshot_id::text,
        'time',iq_occurred_text,
        'datacontenttype','application/json',
        'traceparent','00-' || requested_trace_id::text || '-' ||
                      iq_span_id::text || '-01',
        'data',iq_data);
    iq_payload_utf8 := convert_to(
        ingestion_quality.iq_json_canonical(iq_event), 'UTF8');
    iq_payload_digest := encode(sha256(iq_payload_utf8), 'hex');
    if octet_length(iq_payload_utf8) not between 2 and 65536 then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_DELETION_RESULT_PAYLOAD_INVALID';
    end if;

    if iq_authority_usable then
        update ingestion_quality.iq_quality_snapshot_retention_authority_evidence
           set consumed_at = iq_authority.trusted_observed_at,
               execution_id = requested_execution_id
         where authority_evidence_id = requested_authority_evidence_id
           and consumed_at is null
           and execution_id is null;
        if not found then
            raise exception using
                errcode = 'serialization_failure',
                message = 'INGESTION_QUALITY_RETENTION_AUTHORITY_CONSUME_RACE';
        end if;
    end if;

    if iq_result = 'completed' then
        delete from ingestion_quality.iq_quality_snapshot_metric
         where snapshot_id = requested_snapshot_id;
        get diagnostics iq_deleted_count = row_count;
        if iq_deleted_count <> iq_metric_count then
            raise exception using
                errcode = 'serialization_failure',
                message = 'INGESTION_QUALITY_RETENTION_SNAPSHOT_RACE';
        end if;
        delete from ingestion_quality.iq_quality_snapshot_impact_scope
         where snapshot_id = requested_snapshot_id;
        get diagnostics iq_deleted_count = row_count;
        if iq_deleted_count <> iq_impact_count then
            raise exception using
                errcode = 'serialization_failure',
                message = 'INGESTION_QUALITY_RETENTION_SNAPSHOT_RACE';
        end if;
        delete from ingestion_quality.iq_quality_snapshot
         where snapshot_id = requested_snapshot_id;
        if not found then
            raise exception using
                errcode = 'serialization_failure',
                message = 'INGESTION_QUALITY_RETENTION_SNAPSHOT_RACE';
        end if;
        insert into ingestion_quality.iq_quality_snapshot_deletion_result
          (result_event_id, execution_id, snapshot_id, snapshot_immutable_hash,
           scope_digest, result, transaction_id, transaction_evidence_digest,
           authority_evidence_id, aggregate_version, supersedes_result_event_id,
           occurred_at, payload_utf8, payload_digest)
        values
          (requested_result_event_id, requested_execution_id, requested_snapshot_id,
           requested_snapshot_immutable_hash, requested_scope_digest, 'completed',
           iq_transaction_id, iq_transaction_digest,
           requested_authority_evidence_id, iq_result_version,
           iq_supersedes_result_event_id, iq_occurred_at,
           iq_payload_utf8, iq_payload_digest);
    else
        insert into ingestion_quality.iq_quality_snapshot_deletion_result
          (result_event_id, execution_id, snapshot_id, snapshot_immutable_hash,
           scope_digest, result, blocker_code, authority_evidence_id,
           aggregate_version, supersedes_result_event_id, occurred_at,
           payload_utf8, payload_digest)
        values
          (requested_result_event_id, requested_execution_id, requested_snapshot_id,
           requested_snapshot_immutable_hash, requested_scope_digest, 'blocked',
           iq_blocker_codes ->> 0, requested_authority_evidence_id,
           iq_result_version, iq_supersedes_result_event_id, iq_occurred_at,
           iq_payload_utf8, iq_payload_digest);
    end if;
    insert into ingestion_quality.iq_batch_quality_outbox
      (event_id, aggregate_id, aggregate_version, event_type, schema_version,
       payload_utf8, payload_digest, available_at, created_at)
    values
      (requested_result_event_id, requested_execution_id, iq_result_version,
       'scholarsense.ingestion-quality.quality-snapshot.deletion-result.v1',
       'QUALITY-SNAPSHOT-DELETION-RESULT-1.1.0', iq_payload_utf8,
       iq_payload_digest, iq_statement_at, iq_statement_at);
    return iq_result;
end;
$$;

revoke all on function ingestion_quality.iq_execute_quality_snapshot_retention(
    uuid, uuid, uuid, character, character, uuid, character)
    from public;

alter table ingestion_quality.iq_data_batch
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_normalized_fact
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_batch_quality_measurement
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_batch_quality_operand
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_batch_quality_impact_scope
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_snapshot
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_snapshot_audit_token_binding
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_snapshot_metric
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_snapshot_impact_scope
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_batch_idempotency
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_frozen_qmdp_policy
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_batch_quality_outbox
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_snapshot_retention_authority_evidence
    owner to scholarsense_ingestion_quality_batch_owner;
alter table ingestion_quality.iq_quality_snapshot_deletion_result
    owner to scholarsense_ingestion_quality_batch_owner;
alter view ingestion_quality.iq_published_normalized_fact
    owner to scholarsense_ingestion_quality_batch_owner;

do $$
declare
    iq_function regprocedure;
begin
    for iq_function in
      select oid::regprocedure
        from pg_catalog.pg_proc
       where pronamespace = 'ingestion_quality'::regnamespace
         and proname = any(array[
           'iq_utf8_scalar_count',
           'iq_json_exact_object',
           'iq_json_quote_utf8',
           'iq_json_canonical',
           'iq_qshm_canonical',
           'iq_canonical_instant',
           'iq_json_uuid_v7',
           'iq_json_digest',
           'iq_json_safe_nonnegative_integer',
           'iq_json_timestamp',
           'iq_deletion_result_target_shape',
           'iq_deletion_result_payload_shape',
           'iq_qmdp_source',
           'iq_qmdp_ordered_definitions',
           'iq_require_production_watermark',
           'iq_require_production_impact_scope',
           'iq_expected_sealed_contract',
           'iq_guard_data_batch_state',
           'iq_guard_normalized_fact_insert',
           'iq_reject_immutable_batch_evidence',
           'iq_guard_measurement_change',
           'iq_guard_batch_quality_impact_scope_change',
           'iq_guard_snapshot_insert',
           'iq_guard_batch_outbox_update',
           'iq_guard_retention_authority_evidence',
           'iq_require_exclusive_workload',
           'iq_claim_next_batch_quality_outbox',
           'iq_release_batch_quality_outbox',
           'iq_deliver_batch_quality_outbox',
           'iq_fail_batch_quality_outbox',
           'iq_claim_batch_command',
           'iq_inspect_batch_command_precedence',
           'iq_complete_batch_command',
           'iq_validate_batch_audit_payload',
           'iq_append_batch_audit',
           'iq_receive_data_batch',
           'iq_append_normalized_fact',
           'iq_record_batch_quality_measurement',
           'iq_record_batch_quality_impact_scope',
           'iq_seal_data_batch',
           'iq_qmdp_expected_metrics',
           'iq_qshm_expected_hash',
           'iq_validate_batch_business_payload',
           'iq_validate_batch_published_payload',
           'iq_commit_batch_quality_evaluation',
           'iq_publish_data_batch',
           'iq_add_catalog_source',
           'iq_add_catalog_dependency',
           'iq_record_catalog_validation',
           'iq_publish_catalog',
           'iq_record_historical_window',
           'iq_accept_subject_mapping_event',
           'iq_reconcile_subject_mapping_consumer',
           'iq_enqueue_mapping_recompute',
           'iq_record_mapping_recompute_plan',
           'iq_claim_mapping_recompute_job',
           'iq_checkpoint_mapping_recompute_job',
           'iq_complete_mapping_recompute_job',
           'iq_fail_mapping_recompute_job',
           'iq_requeue_mapping_recompute_job',
           'iq_cancel_mapping_recompute_job',
           'iq_cleanup_expired',
           'iq_retention_authority_payload_shape',
           'iq_ingest_quality_snapshot_retention_authority',
           'iq_find_next_due_quality_snapshot_retention',
           'iq_execute_quality_snapshot_retention'])
    loop
        execute format('revoke all on function %s from public', iq_function);
        execute format(
            'alter function %s owner to scholarsense_ingestion_quality_batch_owner',
            iq_function);
    end loop;
end
$$;

revoke all privileges on table ingestion_quality.iq_data_batch,
    ingestion_quality.iq_normalized_fact,
    ingestion_quality.iq_batch_quality_measurement,
    ingestion_quality.iq_batch_quality_operand,
    ingestion_quality.iq_batch_quality_impact_scope,
    ingestion_quality.iq_quality_snapshot,
    ingestion_quality.iq_quality_snapshot_audit_token_binding,
    ingestion_quality.iq_quality_snapshot_metric,
    ingestion_quality.iq_quality_snapshot_impact_scope,
    ingestion_quality.iq_batch_idempotency,
    ingestion_quality.iq_frozen_qmdp_policy,
    ingestion_quality.iq_batch_quality_outbox,
    ingestion_quality.iq_quality_snapshot_retention_authority_evidence,
    ingestion_quality.iq_quality_snapshot_deletion_result,
    ingestion_quality.iq_published_normalized_fact
    from public;
revoke all privileges on table ingestion_quality.iq_data_batch,
    ingestion_quality.iq_normalized_fact,
    ingestion_quality.iq_batch_quality_measurement,
    ingestion_quality.iq_batch_quality_operand,
    ingestion_quality.iq_batch_quality_impact_scope,
    ingestion_quality.iq_quality_snapshot,
    ingestion_quality.iq_quality_snapshot_audit_token_binding,
    ingestion_quality.iq_quality_snapshot_metric,
    ingestion_quality.iq_quality_snapshot_impact_scope,
    ingestion_quality.iq_batch_idempotency,
    ingestion_quality.iq_frozen_qmdp_policy,
    ingestion_quality.iq_batch_quality_outbox,
    ingestion_quality.iq_quality_snapshot_retention_authority_evidence,
    ingestion_quality.iq_quality_snapshot_deletion_result,
    ingestion_quality.iq_published_normalized_fact
    from scholarsense_ingestion_quality_online;
revoke all privileges on table ingestion_quality.iq_data_batch,
    ingestion_quality.iq_normalized_fact,
    ingestion_quality.iq_batch_quality_measurement,
    ingestion_quality.iq_batch_quality_operand,
    ingestion_quality.iq_batch_quality_impact_scope,
    ingestion_quality.iq_quality_snapshot,
    ingestion_quality.iq_quality_snapshot_audit_token_binding,
    ingestion_quality.iq_quality_snapshot_metric,
    ingestion_quality.iq_quality_snapshot_impact_scope,
    ingestion_quality.iq_batch_idempotency,
    ingestion_quality.iq_frozen_qmdp_policy,
    ingestion_quality.iq_batch_quality_outbox,
    ingestion_quality.iq_quality_snapshot_retention_authority_evidence,
    ingestion_quality.iq_quality_snapshot_deletion_result,
    ingestion_quality.iq_published_normalized_fact
    from scholarsense_ingestion_quality_quality_worker;
revoke all privileges on table ingestion_quality.iq_data_batch,
    ingestion_quality.iq_normalized_fact,
    ingestion_quality.iq_batch_quality_measurement,
    ingestion_quality.iq_batch_quality_operand,
    ingestion_quality.iq_batch_quality_impact_scope,
    ingestion_quality.iq_quality_snapshot,
    ingestion_quality.iq_quality_snapshot_metric,
    ingestion_quality.iq_quality_snapshot_impact_scope,
    ingestion_quality.iq_batch_idempotency,
    ingestion_quality.iq_frozen_qmdp_policy,
    ingestion_quality.iq_batch_quality_outbox,
    ingestion_quality.iq_quality_snapshot_retention_authority_evidence,
    ingestion_quality.iq_quality_snapshot_deletion_result,
    ingestion_quality.iq_published_normalized_fact
    from scholarsense_ingestion_quality_relay;
revoke all privileges on table ingestion_quality.iq_data_batch,
    ingestion_quality.iq_normalized_fact,
    ingestion_quality.iq_batch_quality_measurement,
    ingestion_quality.iq_batch_quality_operand,
    ingestion_quality.iq_batch_quality_impact_scope,
    ingestion_quality.iq_quality_snapshot,
    ingestion_quality.iq_quality_snapshot_metric,
    ingestion_quality.iq_quality_snapshot_impact_scope,
    ingestion_quality.iq_batch_idempotency,
    ingestion_quality.iq_frozen_qmdp_policy,
    ingestion_quality.iq_batch_quality_outbox,
    ingestion_quality.iq_quality_snapshot_retention_authority_evidence,
    ingestion_quality.iq_quality_snapshot_deletion_result,
    ingestion_quality.iq_published_normalized_fact
    from scholarsense_ingestion_quality_retention_executor;
revoke all privileges on table ingestion_quality.iq_data_batch,
    ingestion_quality.iq_normalized_fact,
    ingestion_quality.iq_batch_quality_measurement,
    ingestion_quality.iq_batch_quality_operand,
    ingestion_quality.iq_batch_quality_impact_scope,
    ingestion_quality.iq_quality_snapshot,
    ingestion_quality.iq_quality_snapshot_metric,
    ingestion_quality.iq_quality_snapshot_impact_scope,
    ingestion_quality.iq_batch_idempotency,
    ingestion_quality.iq_frozen_qmdp_policy,
    ingestion_quality.iq_batch_quality_outbox,
    ingestion_quality.iq_quality_snapshot_retention_authority_evidence,
    ingestion_quality.iq_quality_snapshot_deletion_result,
    ingestion_quality.iq_published_normalized_fact
    from scholarsense_ingestion_quality_consumer_registry_authority;

grant usage on schema ingestion_quality
    to scholarsense_ingestion_quality_batch_owner;
grant usage on schema ingestion_quality
    to scholarsense_ingestion_quality_quality_worker;
grant usage on schema ingestion_quality
    to scholarsense_ingestion_quality_retention_executor;
grant usage on schema ingestion_quality
    to scholarsense_ingestion_quality_consumer_registry_authority;

-- V11/V13 online and retention SECURITY DEFINER routines are reassigned above
-- to the restricted NOLOGIN owner. Give that owner only the relation access
-- those already-published routines require; workload roles retain zero raw
-- table authority and can act only through the closed routines below.
grant select, update, delete
    on ingestion_quality.iq_data_source_catalog
    to scholarsense_ingestion_quality_batch_owner;
grant select, insert, update
    on ingestion_quality.iq_source_id_reservation,
       ingestion_quality.iq_dependency_id_reservation
    to scholarsense_ingestion_quality_batch_owner;
grant select, insert, delete
    on ingestion_quality.iq_source_contract,
       ingestion_quality.iq_dependency_binding,
       ingestion_quality.iq_catalog_validation_attempt,
       ingestion_quality.iq_catalog_evidence
    to scholarsense_ingestion_quality_batch_owner;
grant select, insert, update
    on ingestion_quality.iq_catalog_current
    to scholarsense_ingestion_quality_batch_owner;
grant select, delete
    on ingestion_quality.iq_catalog_idempotency,
       ingestion_quality.iq_local_audit_fact,
       ingestion_quality.iq_local_audit_outbox
    to scholarsense_ingestion_quality_batch_owner;
grant insert
    on ingestion_quality.iq_historical_window
    to scholarsense_ingestion_quality_batch_owner;
grant select, insert, update
    on ingestion_quality.iq_subject_mapping_consumer_cursor,
       ingestion_quality.iq_mapping_recompute_job
    to scholarsense_ingestion_quality_batch_owner;
grant select, insert
    on ingestion_quality.iq_subject_mapping_event_inbox,
       ingestion_quality.iq_subject_mapping_event_quarantine,
       ingestion_quality.iq_mapping_recompute_request
    to scholarsense_ingestion_quality_batch_owner;
grant insert
    on ingestion_quality.iq_mapping_recompute_result,
       ingestion_quality.iq_mapping_recompute_outbox
    to scholarsense_ingestion_quality_batch_owner;

grant insert (
    audit_id, actor_search_token, action, result, batch_id, snapshot_id, aggregate_version,
    trace_id, occurred_at, authorization_context, request_digest, expires_at)
    on ingestion_quality.iq_local_audit_fact
    to scholarsense_ingestion_quality_batch_owner;
grant select (audit_id, batch_id, action, result, aggregate_version)
    on ingestion_quality.iq_local_audit_fact
    to scholarsense_ingestion_quality_batch_owner;
grant insert (
    event_id, audit_id, event_type, schema_version, producer, payload,
    payload_digest, available_at, created_at)
    on ingestion_quality.iq_local_audit_outbox
    to scholarsense_ingestion_quality_batch_owner;

-- Normalize the legacy online routine ACLs after owner reassignment. Dynamic
-- regprocedure rendering preserves each published identity signature exactly.
do $$
declare
    iq_function regprocedure;
begin
    for iq_function in
      select oid::regprocedure
        from pg_catalog.pg_proc
       where pronamespace = 'ingestion_quality'::regnamespace
         and proname = any(array[
           'iq_add_catalog_source',
           'iq_add_catalog_dependency',
           'iq_record_catalog_validation',
           'iq_publish_catalog',
           'iq_record_historical_window',
           'iq_accept_subject_mapping_event',
           'iq_reconcile_subject_mapping_consumer',
           'iq_enqueue_mapping_recompute',
           'iq_record_mapping_recompute_plan',
           'iq_claim_mapping_recompute_job',
           'iq_checkpoint_mapping_recompute_job',
           'iq_complete_mapping_recompute_job',
           'iq_fail_mapping_recompute_job',
           'iq_requeue_mapping_recompute_job',
           'iq_cancel_mapping_recompute_job'])
    loop
        execute format(
          'revoke all on function %s from public, '
          'scholarsense_ingestion_quality_online, '
          'scholarsense_ingestion_quality_quality_worker, '
          'scholarsense_ingestion_quality_relay, '
          'scholarsense_ingestion_quality_retention_executor, '
          'scholarsense_ingestion_quality_consumer_registry_authority',
          iq_function);
        execute format(
          'grant execute on function %s to '
          'scholarsense_ingestion_quality_online', iq_function);
    end loop;
end
$$;

grant select on table ingestion_quality.iq_data_batch,
    ingestion_quality.iq_normalized_fact,
    ingestion_quality.iq_batch_quality_measurement,
    ingestion_quality.iq_batch_quality_operand,
    ingestion_quality.iq_batch_quality_impact_scope,
    ingestion_quality.iq_quality_snapshot,
    ingestion_quality.iq_quality_snapshot_metric,
    ingestion_quality.iq_quality_snapshot_impact_scope,
    ingestion_quality.iq_batch_idempotency
    to scholarsense_ingestion_quality_quality_worker;
grant select on table ingestion_quality.iq_batch_quality_outbox
    to scholarsense_ingestion_quality_relay;

revoke all on function
    ingestion_quality.iq_claim_next_batch_quality_outbox()
    from public,
         scholarsense_ingestion_quality_online,
         scholarsense_ingestion_quality_quality_worker,
         scholarsense_ingestion_quality_relay,
         scholarsense_ingestion_quality_retention_executor,
         scholarsense_ingestion_quality_consumer_registry_authority;
revoke all on function
    ingestion_quality.iq_release_batch_quality_outbox(uuid, bigint)
    from public,
         scholarsense_ingestion_quality_online,
         scholarsense_ingestion_quality_quality_worker,
         scholarsense_ingestion_quality_relay,
         scholarsense_ingestion_quality_retention_executor,
         scholarsense_ingestion_quality_consumer_registry_authority;
revoke all on function
    ingestion_quality.iq_deliver_batch_quality_outbox(uuid, bigint)
    from public,
         scholarsense_ingestion_quality_online,
         scholarsense_ingestion_quality_quality_worker,
         scholarsense_ingestion_quality_relay,
         scholarsense_ingestion_quality_retention_executor,
         scholarsense_ingestion_quality_consumer_registry_authority;
revoke all on function
    ingestion_quality.iq_fail_batch_quality_outbox(uuid, bigint)
    from public,
         scholarsense_ingestion_quality_online,
         scholarsense_ingestion_quality_quality_worker,
         scholarsense_ingestion_quality_relay,
         scholarsense_ingestion_quality_retention_executor,
         scholarsense_ingestion_quality_consumer_registry_authority;
grant execute on function
    ingestion_quality.iq_claim_next_batch_quality_outbox()
    to scholarsense_ingestion_quality_relay;
grant execute on function
    ingestion_quality.iq_release_batch_quality_outbox(uuid, bigint)
    to scholarsense_ingestion_quality_relay;
grant execute on function
    ingestion_quality.iq_deliver_batch_quality_outbox(uuid, bigint)
    to scholarsense_ingestion_quality_relay;
grant execute on function
    ingestion_quality.iq_fail_batch_quality_outbox(uuid, bigint)
    to scholarsense_ingestion_quality_relay;

grant execute on function ingestion_quality.iq_receive_data_batch(
    uuid, uuid, varchar, bytea, bigint, uuid, uuid, varchar, timestamptz,
    character, timestamptz, character, character, character, jsonb, character)
    to scholarsense_ingestion_quality_quality_worker;
grant execute on function ingestion_quality.iq_inspect_batch_command_precedence(
    character, varchar, character)
    to scholarsense_ingestion_quality_quality_worker;
grant execute on function ingestion_quality.iq_append_normalized_fact(
    uuid, bytea, varchar, bytea, bigint, varchar, character, uuid, character,
    timestamptz) to scholarsense_ingestion_quality_quality_worker;
grant execute on function ingestion_quality.iq_record_batch_quality_measurement(
    uuid, varchar, integer, boolean, jsonb, character, timestamptz)
    to scholarsense_ingestion_quality_quality_worker;
grant execute on function ingestion_quality.iq_record_batch_quality_impact_scope(
    uuid, bytea, timestamptz)
    to scholarsense_ingestion_quality_quality_worker;
grant execute on function ingestion_quality.iq_seal_data_batch(
    uuid, uuid, bigint, bigint, bigint, bigint, timestamptz, timestamptz,
    timestamptz, varchar, bytea, varchar, character, varchar, character,
    varchar, character, varchar, character, timestamptz, timestamptz,
    timestamptz, varchar, character, jsonb, timestamptz, character, character,
    character, jsonb, character) to scholarsense_ingestion_quality_quality_worker;
grant execute on function ingestion_quality.iq_commit_batch_quality_evaluation(
    uuid, uuid, bigint, uuid, varchar, varchar, varchar, varchar,
    timestamptz, character,
    character, character, jsonb, character, character, jsonb, character,
    uuid, varchar, varchar, bytea, character)
    to scholarsense_ingestion_quality_quality_worker;
grant execute on function ingestion_quality.iq_publish_data_batch(
    uuid, uuid, bigint, timestamptz, character, character, character, jsonb,
    character, uuid, varchar, varchar, bytea, character)
    to scholarsense_ingestion_quality_quality_worker;
grant execute on function ingestion_quality.iq_execute_quality_snapshot_retention(
    uuid, uuid, uuid, character, character, uuid, character)
    to scholarsense_ingestion_quality_retention_executor;
grant execute on function
    ingestion_quality.iq_find_next_due_quality_snapshot_retention()
    to scholarsense_ingestion_quality_retention_executor;
grant execute on function
    ingestion_quality.iq_ingest_quality_snapshot_retention_authority(
      uuid, bytea, character)
    to scholarsense_ingestion_quality_consumer_registry_authority;

revoke execute on function ingestion_quality.iq_cleanup_expired(timestamptz)
    from public,
         scholarsense_ingestion_quality_online,
         scholarsense_ingestion_quality_quality_worker,
         scholarsense_ingestion_quality_relay,
         scholarsense_ingestion_quality_retention_executor,
         scholarsense_ingestion_quality_consumer_registry_authority;
grant execute on function ingestion_quality.iq_cleanup_expired(timestamptz)
    to scholarsense_ingestion_quality_retention_executor;

create function ingestion_quality.iq_find_assessed_quality_snapshot_ids(
    requested_source_id varchar,
    requested_overall_result varchar,
    requested_evaluated_from timestamptz,
    requested_evaluated_to timestamptz,
    requested_sort_field varchar,
    requested_sort_direction varchar,
    requested_after_evaluated_at timestamptz,
    requested_after_snapshot_id uuid,
    requested_limit integer)
returns table(snapshot_id uuid)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    if requested_limit not between 1 and 101
       or (requested_source_id is not null
           and requested_source_id !~ '^SRC-P[01]-[A-Z-]+-[0-9]{3}$')
       or (requested_overall_result is not null
           and requested_overall_result not in ('quality-passed','quality-failed'))
       or requested_sort_field <> 'evaluatedAt'
       or requested_sort_direction not in ('asc','desc')
       or (requested_evaluated_from is not null
           and requested_evaluated_to is not null
           and requested_evaluated_from >= requested_evaluated_to)
       or ((requested_after_evaluated_at is null)
           <> (requested_after_snapshot_id is null)) then
        raise exception using
            errcode = 'invalid_parameter_value',
            message = 'INGESTION_QUALITY_SNAPSHOT_QUERY_INVALID';
    end if;
    return query
    select snapshot.snapshot_id
      from ingestion_quality.iq_quality_snapshot snapshot
     where (requested_source_id is null
            or snapshot.source_id = requested_source_id)
       and (requested_overall_result is null
            or snapshot.overall_result = requested_overall_result)
       and (requested_evaluated_from is null
            or snapshot.evaluated_at >= requested_evaluated_from)
       and (requested_evaluated_to is null
            or snapshot.evaluated_at < requested_evaluated_to)
       and (requested_after_evaluated_at is null
            or requested_sort_direction = 'desc'
               and (snapshot.evaluated_at, snapshot.snapshot_id)
                   < (requested_after_evaluated_at, requested_after_snapshot_id)
            or requested_sort_direction = 'asc'
               and (snapshot.evaluated_at, snapshot.snapshot_id)
                   > (requested_after_evaluated_at, requested_after_snapshot_id))
     order by
       case when requested_sort_direction = 'asc' then snapshot.evaluated_at end asc,
       case when requested_sort_direction = 'asc' then snapshot.snapshot_id end asc,
       case when requested_sort_direction = 'desc' then snapshot.evaluated_at end desc,
       case when requested_sort_direction = 'desc' then snapshot.snapshot_id end desc
     limit requested_limit;
end
$$;

create function ingestion_quality.iq_find_assessed_quality_snapshot(
    requested_snapshot_id uuid)
returns table(
    snapshot_id uuid,
    batch_id uuid,
    domain_tag varchar,
    hash_profile_version varchar,
    hash_profile_digest character,
    source_id varchar,
    assessed_batch_status varchar,
    overall_result varchar,
    observation_start_at timestamptz,
    observation_end_at timestamptz,
    cutoff_at timestamptz,
    watermark_utf8 bytea,
    source_owner_ref varchar,
    approval_ref varchar,
    effective_at timestamptz,
    retention_schedule_version varchar,
    qmdp_version varchar,
    qmdp_digest character,
    quality_gate_version varchar,
    quality_gate_digest character,
    canonicalization_profile varchar,
    manifest_digest character,
    source_schema_version varchar,
    source_schema_digest character,
    lineage_id uuid,
    supersedes_snapshot_id uuid,
    evaluated_at timestamptz,
    trace_id character,
    aggregate_version bigint,
    immutable_hash character)
language sql
stable
security definer
set search_path = pg_catalog
as $$
select snapshot.snapshot_id, snapshot.batch_id, snapshot.domain_tag,
       snapshot.hash_profile_version, snapshot.hash_profile_digest,
       snapshot.source_id, snapshot.assessed_batch_status, snapshot.overall_result,
       snapshot.observation_start_at, snapshot.observation_end_at,
       snapshot.cutoff_at, snapshot.watermark_utf8, snapshot.source_owner_ref,
       snapshot.approval_ref, snapshot.effective_at,
       snapshot.retention_schedule_version, snapshot.qmdp_version,
       snapshot.qmdp_digest, snapshot.quality_gate_version,
       snapshot.quality_gate_digest, snapshot.canonicalization_profile,
       snapshot.manifest_digest, snapshot.source_schema_version,
       snapshot.source_schema_digest, snapshot.lineage_id,
       snapshot.supersedes_snapshot_id, snapshot.evaluated_at,
       snapshot.trace_id, snapshot.aggregate_version, snapshot.immutable_hash
  from ingestion_quality.iq_quality_snapshot snapshot
 where snapshot.snapshot_id = requested_snapshot_id
$$;

create function ingestion_quality.iq_find_assessed_quality_snapshot_metrics(
    requested_snapshot_id uuid)
returns table(
    metric_id varchar,
    formula_id varchar,
    formula_version varchar,
    result varchar,
    applicable boolean,
    numerator bigint,
    denominator bigint,
    value_basis_points bigint,
    unit varchar,
    operator varchar,
    threshold_numerator bigint,
    threshold_denominator bigint,
    boundary varchar,
    reason_code varchar)
language sql
stable
security definer
set search_path = pg_catalog
as $$
select metric.metric_id, metric.formula_id, metric.formula_version,
       metric.result, metric.applicable, metric.numerator, metric.denominator,
       metric.value_basis_points, metric.unit, metric.operator,
       metric.threshold_numerator, metric.threshold_denominator,
       metric.boundary, metric.reason_code
  from ingestion_quality.iq_quality_snapshot_metric metric
 where metric.snapshot_id = requested_snapshot_id
 order by metric.metric_ordinal
$$;

create function ingestion_quality.iq_find_assessed_quality_snapshot_impact_scopes(
    requested_snapshot_id uuid)
returns table(scope_code_utf8 bytea)
language sql
stable
security definer
set search_path = pg_catalog
as $$
select impact.scope_code_utf8
  from ingestion_quality.iq_quality_snapshot_impact_scope impact
 where impact.snapshot_id = requested_snapshot_id
 order by impact.scope_ordinal
$$;

create function ingestion_quality.iq_require_quality_snapshot_page(
    requested_snapshot_ids uuid[])
returns void
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    if coalesce(cardinality(requested_snapshot_ids), 0) not between 1 and 101
       or exists (
         select 1 from unnest(requested_snapshot_ids) requested(snapshot_id)
          where snapshot_id is null
             or substring(snapshot_id::text, 15, 1) <> '7'
             or substring(snapshot_id::text, 20, 1) not in ('8','9','a','b'))
       or (select count(distinct snapshot_id)
             from unnest(requested_snapshot_ids) requested(snapshot_id))
          <> cardinality(requested_snapshot_ids) then
        raise exception using
            errcode = 'invalid_parameter_value',
            message = 'INGESTION_QUALITY_SNAPSHOT_PAGE_INVALID';
    end if;
end
$$;

revoke all on function ingestion_quality.iq_require_quality_snapshot_page(uuid[])
    from public;
alter function ingestion_quality.iq_require_quality_snapshot_page(uuid[])
    owner to scholarsense_ingestion_quality_batch_owner;

create function ingestion_quality.iq_find_assessed_quality_snapshot_page(
    requested_snapshot_ids uuid[])
returns table(
    snapshot_id uuid,
    batch_id uuid,
    domain_tag varchar,
    hash_profile_version varchar,
    hash_profile_digest character,
    source_id varchar,
    assessed_batch_status varchar,
    overall_result varchar,
    observation_start_at timestamptz,
    observation_end_at timestamptz,
    cutoff_at timestamptz,
    watermark_utf8 bytea,
    source_owner_ref varchar,
    approval_ref varchar,
    effective_at timestamptz,
    retention_schedule_version varchar,
    qmdp_version varchar,
    qmdp_digest character,
    quality_gate_version varchar,
    quality_gate_digest character,
    canonicalization_profile varchar,
    manifest_digest character,
    source_schema_version varchar,
    source_schema_digest character,
    lineage_id uuid,
    supersedes_snapshot_id uuid,
    evaluated_at timestamptz,
    trace_id character,
    aggregate_version bigint,
    immutable_hash character)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_quality_snapshot_page(requested_snapshot_ids);
    return query
    select snapshot.snapshot_id, snapshot.batch_id, snapshot.domain_tag,
           snapshot.hash_profile_version, snapshot.hash_profile_digest,
           snapshot.source_id, snapshot.assessed_batch_status, snapshot.overall_result,
           snapshot.observation_start_at, snapshot.observation_end_at,
           snapshot.cutoff_at, snapshot.watermark_utf8, snapshot.source_owner_ref,
           snapshot.approval_ref, snapshot.effective_at,
           snapshot.retention_schedule_version, snapshot.qmdp_version,
           snapshot.qmdp_digest, snapshot.quality_gate_version,
           snapshot.quality_gate_digest, snapshot.canonicalization_profile,
           snapshot.manifest_digest, snapshot.source_schema_version,
           snapshot.source_schema_digest, snapshot.lineage_id,
           snapshot.supersedes_snapshot_id, snapshot.evaluated_at,
           snapshot.trace_id, snapshot.aggregate_version, snapshot.immutable_hash
      from unnest(requested_snapshot_ids) with ordinality
        requested(requested_id, requested_ordinal)
      join ingestion_quality.iq_quality_snapshot snapshot
        on snapshot.snapshot_id = requested_id
     order by requested_ordinal;
end
$$;

create function ingestion_quality.iq_find_assessed_quality_snapshot_page_metrics(
    requested_snapshot_ids uuid[])
returns table(
    snapshot_id uuid,
    metric_id varchar,
    formula_id varchar,
    formula_version varchar,
    result varchar,
    applicable boolean,
    numerator bigint,
    denominator bigint,
    value_basis_points bigint,
    unit varchar,
    operator varchar,
    threshold_numerator bigint,
    threshold_denominator bigint,
    boundary varchar,
    reason_code varchar)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_quality_snapshot_page(requested_snapshot_ids);
    return query
    select metric.snapshot_id, metric.metric_id, metric.formula_id,
           metric.formula_version, metric.result, metric.applicable,
           metric.numerator, metric.denominator, metric.value_basis_points,
           metric.unit, metric.operator, metric.threshold_numerator,
           metric.threshold_denominator, metric.boundary, metric.reason_code
      from unnest(requested_snapshot_ids) with ordinality
        requested(requested_id, requested_ordinal)
      join ingestion_quality.iq_quality_snapshot_metric metric
        on metric.snapshot_id = requested_id
     order by requested_ordinal, metric.metric_ordinal;
end
$$;

create function ingestion_quality.iq_find_assessed_quality_snapshot_page_impact_scopes(
    requested_snapshot_ids uuid[])
returns table(snapshot_id uuid, scope_code_utf8 bytea)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    perform ingestion_quality.iq_require_quality_snapshot_page(requested_snapshot_ids);
    return query
    select impact.snapshot_id, impact.scope_code_utf8
      from unnest(requested_snapshot_ids) with ordinality
        requested(requested_id, requested_ordinal)
      join ingestion_quality.iq_quality_snapshot_impact_scope impact
        on impact.snapshot_id = requested_id
     order by requested_ordinal, impact.scope_ordinal;
end
$$;

create function ingestion_quality.iq_resolve_quality_snapshot_source(
    requested_snapshot_token_digest character)
returns table(source_id varchar, object_version bigint)
language plpgsql
stable
security definer
set search_path = pg_catalog
as $$
begin
    if requested_snapshot_token_digest is null
       or requested_snapshot_token_digest !~ '^[0-9a-f]{64}$' then
        raise exception using
            errcode = 'invalid_parameter_value',
            message = 'INGESTION_QUALITY_SNAPSHOT_TOKEN_INVALID';
    end if;
    return query
    select snapshot.source_id, snapshot.aggregate_version
      from ingestion_quality.iq_quality_snapshot snapshot
     where snapshot.snapshot_token_digest = requested_snapshot_token_digest;
end
$$;

do $$
declare
    iq_function regprocedure;
begin
    for iq_function in
      select oid::regprocedure
        from pg_catalog.pg_proc
       where pronamespace = 'ingestion_quality'::regnamespace
         and proname = any(array[
           'iq_find_assessed_quality_snapshot_ids',
           'iq_find_assessed_quality_snapshot',
           'iq_find_assessed_quality_snapshot_metrics',
           'iq_find_assessed_quality_snapshot_impact_scopes',
           'iq_find_assessed_quality_snapshot_page',
           'iq_find_assessed_quality_snapshot_page_metrics',
           'iq_find_assessed_quality_snapshot_page_impact_scopes',
           'iq_resolve_quality_snapshot_source'])
    loop
        execute format('revoke all on function %s from public', iq_function);
        execute format(
          'alter function %s owner to scholarsense_ingestion_quality_batch_owner',
          iq_function);
        execute format(
          'grant execute on function %s to scholarsense_ingestion_quality_online',
          iq_function);
    end loop;
end
$$;

create function ingestion_quality.iq_append_quality_snapshot_read_audit(
    requested_audit_id uuid,
    requested_event_id uuid,
    requested_snapshot_id uuid,
    requested_actor_search_token varchar,
    requested_object_search_token varchar,
    requested_source_ip_search_token varchar,
    requested_aggregate_search_token varchar,
    requested_action varchar,
    requested_aggregate_version bigint,
    requested_trace_id character,
    requested_occurred_at timestamptz,
    requested_payload jsonb,
    requested_payload_digest character)
returns void
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    iq_snapshot ingestion_quality.iq_quality_snapshot%rowtype;
    iq_token_binding ingestion_quality.iq_quality_snapshot_audit_token_binding%rowtype;
    iq_fact jsonb;
    iq_authorization jsonb;
    iq_time_profile jsonb;
begin
    select snapshot.* into strict iq_snapshot
      from ingestion_quality.iq_quality_snapshot snapshot
     where snapshot.snapshot_id = requested_snapshot_id;
    select binding.* into iq_token_binding
      from ingestion_quality.iq_quality_snapshot_audit_token_binding binding
     where binding.snapshot_id = requested_snapshot_id;
    iq_fact := requested_payload -> 'fact';
    iq_authorization := iq_fact -> 'authorizationContext';
    iq_time_profile := iq_fact -> 'timeSourceProfile';
    if iq_snapshot.aggregate_version <> requested_aggregate_version
       or requested_action not in (
          'quality-snapshot-detail-read','quality-snapshot-metric-read')
       or requested_actor_search_token !~ '^ast_v1_k[0-9]+_[0-9a-f]{64}$'
       or requested_trace_id !~ '^[0-9a-f]{32}$'
       or requested_trace_id ~ '^0{32}$'
       or substring(requested_audit_id::text, 15, 1) <> '7'
       or substring(requested_audit_id::text, 20, 1) not in ('8','9','a','b')
       or substring(requested_event_id::text, 15, 1) <> '7'
       or substring(requested_event_id::text, 20, 1) not in ('8','9','a','b')
       or not ingestion_quality.iq_json_exact_object(requested_payload, array[
          'eventId','auditId','eventType','schemaVersion','producer','createdAt','fact'])
       or not ingestion_quality.iq_json_exact_object(iq_fact, array[
          'auditId','schemaVersion','producerModule','actorType','actorSearchToken',
          'roleIds','authorizationContext','action','objectType','objectSearchToken',
          'outcome','reasonCode','purpose','projectionScope','occurredAt','recordedAt',
          'timeSourceProfile','sourceIpSearchToken','tokenizationProfileVersion',
          'keyVersion','traceId','aggregateType','aggregateIdSearchToken',
          'aggregateVersion','idempotencyKeyDigest','policyVersions',
          'retentionScheduleVersion'])
       or requested_payload ->> 'eventId' <> requested_event_id::text
       or requested_payload ->> 'auditId' <> requested_audit_id::text
       or requested_payload ->> 'eventType' <>
          'ingestion-quality.local-audit-fact.recorded.v1'
       or requested_payload ->> 'schemaVersion' <> 'LOCAL-AUDIT-OUTBOX-1.0.0'
       or requested_payload ->> 'producer' <> 'ingestion-quality'
       or iq_fact ->> 'auditId' <> requested_audit_id::text
       or iq_fact ->> 'schemaVersion' <> 'LOCAL-AUDIT-FACT-1.0.0'
       or iq_fact ->> 'producerModule' <> 'ingestion-quality'
       or iq_fact ->> 'actorType' <> 'USER'
       or iq_fact ->> 'actorSearchToken' <> requested_actor_search_token
       or iq_fact -> 'roleIds' <> '["R6"]'::jsonb
       or iq_fact ->> 'action' <> requested_action
       or iq_fact ->> 'objectType' <> 'quality-snapshot'
       or iq_fact ->> 'objectSearchToken' <> requested_object_search_token
       or requested_object_search_token !~ '^ost_v1_k[0-9]+_[0-9a-f]{64}$'
       or iq_token_binding.snapshot_id is null
       or requested_object_search_token <> iq_token_binding.object_search_token
       or iq_fact ->> 'aggregateType' <> 'quality-snapshot'
       or iq_fact ->> 'aggregateIdSearchToken' <> requested_aggregate_search_token
       or requested_aggregate_search_token !~ '^agt_v1_k[0-9]+_[0-9a-f]{64}$'
       or requested_aggregate_search_token <> iq_token_binding.aggregate_search_token
       or (iq_fact ->> 'aggregateVersion')::bigint <> requested_aggregate_version
       or iq_fact ->> 'traceId' <> requested_trace_id
       or iq_fact ->> 'outcome' <> 'accepted'
       or iq_fact ->> 'reasonCode' <> 'INGESTION_QUALITY_SNAPSHOT_READ_ALLOWED'
       or iq_fact ->> 'purpose' <> 'DATA_QUALITY'
       or iq_fact ->> 'projectionScope' <> 'OWNED_SOURCE'
       or iq_fact ->> 'sourceIpSearchToken' <> requested_source_ip_search_token
       or requested_source_ip_search_token !~ '^ipt_v1_k[0-9]+_[0-9a-f]{64}$'
       or iq_fact ->> 'tokenizationProfileVersion' <> 'AUDIT-TOKENIZATION-1.0.0'
       or iq_fact ->> 'keyVersion' !~ '^k[0-9]+$'
       or split_part(requested_actor_search_token, '_', 3) <> iq_fact ->> 'keyVersion'
       or split_part(requested_object_search_token, '_', 3) <> iq_fact ->> 'keyVersion'
       or split_part(requested_source_ip_search_token, '_', 3) <> iq_fact ->> 'keyVersion'
       or split_part(requested_aggregate_search_token, '_', 3) <> iq_fact ->> 'keyVersion'
       or iq_fact -> 'idempotencyKeyDigest' <> 'null'::jsonb
       or iq_fact -> 'policyVersions' <> '{"roleFieldPolicy":"RFP-1.0.0"}'::jsonb
       or iq_fact ->> 'retentionScheduleVersion' <> 'RS-1.0.0'
       or not ingestion_quality.iq_json_exact_object(iq_authorization, array[
          'decision','policyVersion','scopeCodes','grantSearchTokens','notApplicableReason'])
       or iq_authorization ->> 'decision' <> 'allow'
       or iq_authorization ->> 'policyVersion' <> 'RFP-1.0.0'
       or iq_authorization -> 'scopeCodes' <> '["OWNED_SOURCE"]'::jsonb
       or iq_authorization -> 'grantSearchTokens' <> '[]'::jsonb
       or iq_authorization -> 'notApplicableReason' <> 'null'::jsonb
       or not ingestion_quality.iq_json_exact_object(iq_time_profile, array[
          'sourceId','profileVersion','offsetMs','observedAt','freshUntil','evidenceRef'])
       or iq_time_profile ->> 'profileVersion' <> 'AUDIT-CLOCK-BINDING-1.0.0'
       or jsonb_typeof(iq_time_profile -> 'offsetMs') <> 'number'
       or (iq_time_profile ->> 'observedAt')::timestamptz > requested_occurred_at
       or (iq_time_profile ->> 'freshUntil')::timestamptz < requested_occurred_at
       or (requested_payload ->> 'createdAt')::timestamptz <> requested_occurred_at
       or (iq_fact ->> 'occurredAt')::timestamptz <> requested_occurred_at
       or (iq_fact ->> 'recordedAt')::timestamptz <> requested_occurred_at
       or encode(sha256(convert_to(
          ingestion_quality.iq_json_canonical(requested_payload), 'UTF8')), 'hex')
          <> requested_payload_digest then
        raise exception using
            errcode = 'check_violation',
            message = 'INGESTION_QUALITY_SNAPSHOT_READ_AUDIT_INVALID';
    end if;
    insert into ingestion_quality.iq_local_audit_fact
      (audit_id, actor_search_token, action, result, snapshot_id,
       aggregate_version, trace_id, occurred_at, authorization_context, expires_at)
    values
      (requested_audit_id, requested_actor_search_token, requested_action, 'accepted',
       requested_snapshot_id, requested_aggregate_version, requested_trace_id,
       requested_occurred_at, iq_fact -> 'authorizationContext',
       ((requested_occurred_at at time zone 'UTC') + interval '3 years') at time zone 'UTC');
    insert into ingestion_quality.iq_local_audit_outbox
      (event_id, audit_id, event_type, schema_version, producer,
       payload, payload_digest, available_at, created_at)
    values
      (requested_event_id, requested_audit_id,
       'ingestion-quality.local-audit-fact.recorded.v1',
       'LOCAL-AUDIT-OUTBOX-1.0.0', 'ingestion-quality', requested_payload,
       requested_payload_digest, requested_occurred_at, requested_occurred_at);
end
$$;

revoke all on function ingestion_quality.iq_append_quality_snapshot_read_audit(
    uuid, uuid, uuid, varchar, varchar, varchar, varchar, varchar, bigint,
    character, timestamptz, jsonb, character)
    from public;
alter function ingestion_quality.iq_append_quality_snapshot_read_audit(
    uuid, uuid, uuid, varchar, varchar, varchar, varchar, varchar, bigint,
    character, timestamptz, jsonb, character)
    owner to scholarsense_ingestion_quality_batch_owner;
grant execute on function ingestion_quality.iq_append_quality_snapshot_read_audit(
    uuid, uuid, uuid, varchar, varchar, varchar, varchar, varchar, bigint,
    character, timestamptz, jsonb, character)
    to scholarsense_ingestion_quality_online;
