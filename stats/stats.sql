select
    coalesce(tool_name, 'ALL') as tool_name,
    count(*) as total,
    count(distinct client_id) as users,
    string_agg(distinct nullif(case
        when locale like '%-%' then split_part(locale, '-', 2)
        when locale = 'fr'    then 'FR'
        when locale = 'en'    then 'GB'
        else upper(locale)
    end, ''), ', ' order by nullif(case
        when locale like '%-%' then split_part(locale, '-', 2)
        when locale = 'fr'    then 'FR'
        when locale = 'en'    then 'GB'
        else upper(locale)
    end, '')) as locales,
    string_agg(distinct ai_client, ', ' order by ai_client) filter (where ai_client != 'Unknown MCP client') as ai_clients,
    string_agg(distinct plugin_version, ', ' order by plugin_version desc) as plugin_version,
    string_agg(distinct ide_product || ' ' || ide_version, ', '
               order by ide_product || ' ' || ide_version) as ide
from events
where created_at >= CURRENT_DATE - INTERVAL '0 day'
group by grouping sets ((tool_name), ())
order by (tool_name is not null), total desc;

-- Stats par pays
select
    country,
    case country
        when 'FR' then 'France'
        when 'CN' then 'China'
        when 'US' then 'United States'
        when 'DE' then 'Germany'
        when 'GB' then 'United Kingdom'
        when 'JP' then 'Japan'
        when 'KR' then 'South Korea'
        when 'BR' then 'Brazil'
        when 'IN' then 'India'
        when 'RU' then 'Russia'
        when 'CA' then 'Canada'
        when 'AU' then 'Australia'
        when 'ES' then 'Spain'
        when 'IT' then 'Italy'
        when 'NL' then 'Netherlands'
        when 'PL' then 'Poland'
        when 'SE' then 'Sweden'
        when 'UA' then 'Ukraine'
        when 'TR' then 'Turkey'
        when 'TW' then 'Taiwan'
        when 'CO' then 'Colombia'
        when 'MX' then 'Mexico'
        when 'AR' then 'Argentina'
        when 'CZ' then 'Czech Republic'
        when 'PT' then 'Portugal'
        when 'RO' then 'Romania'
        when 'HU' then 'Hungary'
        else country
        end                                            as country_name,
    calls,
    users,
    ai_clients,
    (
        select string_agg(tool_name, ', ' order by cnt desc)
        from (
                 select tool_name, count(*) as cnt
                 from events e2
                 where (case
                     when e2.locale like '%-%' then split_part(e2.locale, '-', 2)
                     when e2.locale = 'fr'     then 'FR'
                     when e2.locale = 'en'     then 'GB'
                     else upper(e2.locale)
                 end) = s.country
                 group by tool_name
                 order by cnt desc
                 limit 3
             ) top5
    ) as top5_tools
from (
         select
             (case
                 when locale like '%-%' then split_part(locale, '-', 2)
                 when locale = 'fr'     then 'FR'
                 when locale = 'en'     then 'GB'
                 else upper(locale)
             end)                                      as country,
             count(*)                                  as calls,
             count(distinct client_id)                 as users,
             string_agg(distinct ai_client, ', ')
             filter (where ai_client != 'Unknown MCP client'
                 and ai_client is not null)            as ai_clients
         from events
         where locale is not null
           and locale != ''
         group by (case
             when locale like '%-%' then split_part(locale, '-', 2)
             when locale = 'fr'     then 'FR'
             when locale = 'en'     then 'GB'
             else upper(locale)
         end)
     ) s
order by calls desc;
