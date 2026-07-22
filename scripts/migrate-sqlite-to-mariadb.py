#!/usr/bin/env python3
"""Copy the shared ks-Series schema/data from a stopped SQLite node into MariaDB.

The destination schema must already have been initialized by the current plugin
build. Only tables present on both sides are replaced. Destination-only tables
keep their freshly initialized defaults, allowing forward-compatible migrations.
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path

import pymysql


IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def identifier(value: str) -> str:
    if not IDENTIFIER.fullmatch(value):
        raise ValueError(f"unsafe SQL identifier: {value!r}")
    return value


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--report", type=Path)
    return parser.parse_args()


def sqlite_tables(connection: sqlite3.Connection) -> list[str]:
    return [
        identifier(row[0])
        for row in connection.execute(
            "SELECT name FROM sqlite_master "
            "WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        )
    ]


def sqlite_columns(connection: sqlite3.Connection, table: str) -> list[str]:
    return [identifier(row[1]) for row in connection.execute(f'PRAGMA table_info("{table}")')]


def mariadb_tables(cursor: pymysql.cursors.Cursor, database: str) -> list[str]:
    cursor.execute(
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema=%s AND table_type='BASE TABLE' ORDER BY table_name",
        (database,),
    )
    return [identifier(row[0]) for row in cursor.fetchall()]


def mariadb_columns(cursor: pymysql.cursors.Cursor, database: str, table: str) -> list[str]:
    cursor.execute(
        "SELECT column_name FROM information_schema.columns "
        "WHERE table_schema=%s AND table_name=%s ORDER BY ordinal_position",
        (database, table),
    )
    return [identifier(row[0]) for row in cursor.fetchall()]


def main() -> int:
    args = parse_args()
    source = args.source.resolve(strict=True)
    if args.batch_size < 1:
        raise ValueError("batch size must be positive")

    sqlite = sqlite3.connect(f"file:{source.as_posix()}?mode=ro", uri=True)
    sqlite.execute("PRAGMA query_only=ON")
    maria = pymysql.connect(
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
        database=args.database,
        charset="utf8mb4",
        autocommit=False,
    )

    report: dict[str, object] = {"source": str(source), "database": args.database, "tables": []}
    try:
        source_tables = sqlite_tables(sqlite)
        with maria.cursor() as cursor:
            destination_tables = mariadb_tables(cursor, args.database)
            shared = sorted(set(source_tables) & set(destination_tables))
            report["source_only"] = sorted(set(source_tables) - set(destination_tables))
            report["destination_only"] = sorted(set(destination_tables) - set(source_tables))

            cursor.execute("SET FOREIGN_KEY_CHECKS=0")
            cursor.execute("SET UNIQUE_CHECKS=0")
            try:
                for table in shared:
                    source_columns = sqlite_columns(sqlite, table)
                    destination_columns = mariadb_columns(cursor, args.database, table)
                    columns = [column for column in source_columns if column in destination_columns]
                    if not columns:
                        raise RuntimeError(f"no shared columns for table {table}")

                    cursor.execute(f"DELETE FROM `{table}`")
                    quoted_sqlite = ",".join(f'"{column}"' for column in columns)
                    quoted_maria = ",".join(f"`{column}`" for column in columns)
                    placeholders = ",".join(["%s"] * len(columns))
                    insert_sql = f"INSERT INTO `{table}` ({quoted_maria}) VALUES ({placeholders})"

                    source_cursor = sqlite.execute(f'SELECT {quoted_sqlite} FROM "{table}"')
                    copied = 0
                    while True:
                        rows = source_cursor.fetchmany(args.batch_size)
                        if not rows:
                            break
                        cursor.executemany(insert_sql, rows)
                        copied += len(rows)
                    report["tables"].append({"table": table, "rows": copied, "columns": len(columns)})
                maria.commit()
            except BaseException:
                maria.rollback()
                raise
            finally:
                cursor.execute("SET UNIQUE_CHECKS=1")
                cursor.execute("SET FOREIGN_KEY_CHECKS=1")

            mismatches = []
            for entry in report["tables"]:
                table = entry["table"]
                cursor.execute(f"SELECT COUNT(*) FROM `{table}`")
                destination_count = cursor.fetchone()[0]
                if destination_count != entry["rows"]:
                    mismatches.append(
                        {"table": table, "source": entry["rows"], "destination": destination_count}
                    )
            report["mismatches"] = mismatches
            if mismatches:
                raise RuntimeError(f"row-count validation failed: {mismatches}")
    finally:
        sqlite.close()
        maria.close()

    output = json.dumps(report, ensure_ascii=False, indent=2)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output + "\n", encoding="utf-8")
    print(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
