
update pg_settings set setting = 'rhn_server,' || setting where name = 'search_path';

drop function if exists can_server_consume_virt_slot(server_id_in numeric, group_type_in character varying);

update pg_settings set setting = overlay( setting placing '' from 1 for (length('rhn_server')+1) ) where name = 'search_path';
