/*
  Full initialization script for spotify-netease-sync
  Includes:
  1. Original starter schema
  2. Added ums_member table
  3. Music sync tables
*/

SET FOREIGN_KEY_CHECKS=0;

SOURCE ./springboot-starter.sql;
SOURCE ./music_sync.sql;

SET FOREIGN_KEY_CHECKS=1;
