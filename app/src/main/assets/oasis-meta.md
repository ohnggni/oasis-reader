# oasis-meta — 정리된 카탈로그용 메타 API

> **북오아시스 서버용 부가 기능입니다.** 서버 옆에 oasis-meta를 띄우면 OG Reader의 "서버에서 책 찾기"에서
> 시리즈별 표지 목록, 정리된 제목, 파일 날짜 순 정렬, 책 소개를 볼 수 있습니다.
> 없어도 앱은 OPDS 폴더 목록으로 그대로 동작합니다. 필요한 분만 설치하시면 됩니다.

## 설치

준비물: 북오아시스가 Docker(MariaDB)로 돌고 있는 서버, `git`, `docker compose`.

```
git clone https://github.com/ohnggni/oasis-meta
cd oasis-meta
bash install.sh
```

스크립트가 북오아시스 DB 컨테이너를 찾고, 할 일을 요약해 보여준 뒤 확인을 받고 진행합니다.
읽기 전용 DB 계정 만들기 → 컨테이너 실행 → 상태 확인까지 자동입니다.

그다음:

1. 외부에서 쓰려면 리버스 프록시로 `https://meta.<내 도메인>` → `<서버>:5931` 을 연결합니다
2. OG Reader › 설정 › 서버 목록 › 서버 › **메타 API 주소**에 넣고 **연결 확인**을 누릅니다
3. 같은 화면 아래의 **보기 프리셋**에서 카테고리·형식 조합을 만들어 두면 카탈로그에서 한 번에 고를 수 있습니다

- 업데이트: `git pull && bash install.sh` (비밀번호가 든 `.env`는 그대로 둡니다)
- 제거: `docker compose down` (DB 계정까지 지우려면 `DROP USER 'oasis_ro'@'%';`)
- 코드와 자세한 설명: https://github.com/ohnggni/oasis-meta (MIT 라이선스)

## 알아둘 점

- 로그인은 북오아시스 계정 그대로입니다 (OPDS와 같은 아이디/비밀번호).
- ⚠️ 라이브러리별 사용자 권한은 적용하지 않습니다 (성인 DB만 관리자 제한). 여러 사람이 쓰는 서버라면 혼자 쓰는 환경에서만 쓰십시오.
- DB를 **읽기만** 합니다. 책 파일·드라이브에는 접근하지 않고, 책 다운로드와 표지는 지금처럼 북오아시스에서 받습니다.
- 목록은 북오아시스의 스캔 결과를 따릅니다. 아직 스캔되지 않은 파일은 보이지 않습니다.
- 북오아시스 공식 구성 요소가 아닌, 별도로 만든 보조 도구입니다.

<details><summary>하는 일과 원리</summary>
OPDS는 책 목록을 주고받는 표준 형식이라, 앱이 화면을 꾸밀 때 필요한 정보 중 일부(시리즈 묶음, 표지 위치, 파일 날짜 등)는 담기 어렵습니다. 이는 특정 서버가 아니라 OPDS 형식의 성격입니다.

oasis-meta는 같은 서버의 DB를 읽기 전용으로 조회해 그런 정보를 JSON으로 내려 주는 작은 HTTP API입니다.

| 앱 화면 | 쓰는 정보 |
|---|---|
| 시리즈별 표지 목록 | `series_name`(책 파일의 부모 폴더), `cover_url` |
| 화면 표시용 제목 | `display_title` (파일명 기반) |
| 날짜 순 정렬 | `file_mtime` (파일 수정 시각) |
| 책 상세·줄거리 | `summary`, `summary_trust` |
| 카테고리·형식 필터 | `library_id`, `file_format` |

날짜는 파일 수정 시각(`file_mtime`)을 씁니다. DB의 추가일(`created_at`)은 처음 전체 스캔한 날에 몰려 있는 경우가 많아 정렬 기준으로 쓰지 않습니다.
</details>

<details><summary>접속 규칙 (API)</summary>
- **인증:** HTTP Basic — 북오아시스 계정
  - `users` 테이블의 `password_hash`를 북오아시스 로그인과 같은 방식으로 검증
  - 매 요청 해시 검증은 느리니 결과를 몇 분 캐시하길 권장
  - 실패 → `401` + `WWW-Authenticate: Basic realm="oasis-meta"`
- **DB 선택:** 모든 인증 엔드포인트에 쿼리 `db=general`(기본) 또는 `db=adult`
  - `adult`는 `role=admin` 계정만, 아니면 `403 {"error":"forbidden"}`
- **응답:** JSON, UTF-8 (한글 그대로)
- **날짜:** `file_mtime` / `latest_mtime` / `first_mtime` = UTC ISO 8601 (`2022-05-20T01:45:53Z`), `created_at` / `latest_added` = `"YYYY-MM-DD HH:MM:SS"`
- **에러:** 잘못된 숫자 파라미터 → `400 {"error":"bad parameter: ..."}`, 없는 책 → `404 {"error":"not found"}`
- **페이지 응답 공통:** `{"items": [...], "page": 1, "limit": 100, "has_more": true}`
- 언어·프레임워크는 자유입니다 (작성자는 Python + gunicorn 컨테이너). 아래 필드 이름·형식만 맞으면 앱이 동작합니다.
</details>

<details><summary>엔드포인트 (API)</summary>
### `GET /health` (인증 없음)
→ `{"ok": true}`

### `GET /libraries`
```json
{"items": [{"id": 23, "name": "만화"}]}
```

### `GET /series`
시리즈 = `library_id` + `series_name`(책 파일의 부모 폴더명).

| 파라미터 | 설명 | 기본 |
|---|---|---|
| `library_id` | 라이브러리 필터, 콤마로 여러 개 (`3,7,12`) | 전체 |
| `q` | 시리즈명 검색 (공백으로 나눈 단어 AND, 최대 10개) | — |
| `format` | 파일 형식 필터, 콤마 구분 (`cbz,zip`). `library_id`와 함께 적용(AND) | 전체 |
| `sort` | `mtime_desc` `mtime_asc` `added_desc` `added_asc` `name` | `mtime_desc` |
| `page` / `limit` | 1부터 / 1~500 | 1 / 100 |

```json
{
  "items": [{
    "library_id": 28,
    "series_name": "시리즈 이름",
    "book_count": 5,
    "latest_mtime": "2026-10-02T06:01:48Z",
    "first_mtime": "2026-10-02T06:01:48Z",
    "latest_added": "2026-10-03 06:02:05",
    "first_book_id": 268686,
    "cover_url": "/covers/28/book_aa21a06dbb7406596650444dce3431f3.webp"
  }],
  "page": 1, "limit": 1, "has_more": true
}
```

| 필드 | 의미 |
|---|---|
| `series_name` | 부모 폴더명. 앞의 `[만화]` 같은 머리 태그는 제거 |
| `book_count` | 권 수 |
| `latest_mtime` / `first_mtime` | 시리즈 안 파일 mtime 최대/최소 — 앱의 날짜 정렬·표시 |
| `latest_added` | 추가일 최대값 (참고용) |
| `first_book_id` | 시리즈 안 가장 작은 id (대표 권) |
| `cover_url` | 대표 권 표지 경로. 없으면 `null` |

### `GET /books`
권 단위 목록.

| 파라미터 | 설명 | 기본 |
|---|---|---|
| `library_id` | 라이브러리 필터 (콤마로 여러 개) | 전체 |
| `series` | 시리즈명 정확히 일치 (`/series`의 `series_name` 그대로) | — |
| `q` | 제목/시리즈명/저자 검색 (단어 AND, 최대 10개) | — |
| `format` | `cbz,zip` 등 | 전체 |
| `sort` | `mtime_desc` `mtime_asc` `added_desc` `added_asc` `title` | `mtime_desc` |
| `page` / `limit` | 위와 같음 (최대 500) | 1 / 100 |

앱은 시리즈 화면에서 `/books?series=…&library_id=…&sort=title&limit=500`을 부르고, 권 순서는 `display_title` 자연 정렬로 다시 맞춥니다.

### `GET /books/{id}`
권 하나 = `/books`의 item + `summary`, `summary_trust` (이 두 필드는 목록에는 넣지 않음).

| 필드 | 의미 |
|---|---|
| `display_title` | 화면 표시용 제목. 파일명에서 확장자와 끝의 `#숫자`(페이지 수)를 뗀 것 |
| `title` | DB 원본 제목 (앱은 표시에 쓰지 않음) |
| `author` | DB 원본 저자 |
| `file_format` | `epub` `cbz` `zip` `txt` `pdf` … |
| `total_pages` | 0이면 미분석 |
| `download_path` | 서버 기준 다운로드 경로. 앱이 OPDS 서버 호스트를 붙여 받음 |
| `summary` | 줄거리 또는 `null` |
| `summary_trust` | `"ok"` \| `"suspect"` \| `null` |
</details>

<details><summary>표지 (cover_url)</summary>
- DB `books.cover_image` 값(`"{library_id}/book_{md5}.webp"`)을 북오아시스 웹과 같은 규칙으로 바꿉니다: 앞의 `/`·`covers/`를 떼고 `/covers/`를 붙임 → `/covers/23/book_x.webp`
- `http(s)://`·`/api/`로 시작하면 그대로, 빈 값·확장자 없는 값은 `null`
- 앱은 OPDS 서버 호스트 + `cover_url`로 받고, 이미지 형식은 내용으로 판별합니다
- `cover_url`이 없으면 앱이 OPDS 검색으로 썸네일을 한 번 찾아보고, 그래도 없으면 제목 표지를 그립니다
</details>

<details><summary>줄거리 (summary, summary_trust)</summary>
- 출처: DB `books.summary`. 그 권의 값만 내려 주고, 비어 있으면 다른 권 값으로 채우지 않습니다
- 텍스트 정리: 글자 그대로의 `\n`, `<br>`, `<p>`, `<div>` → 줄바꿈 / 영문 HTML 태그만 제거, 엔티티 복원 / `<책소개>` 같은 한글 꺾쇠는 보존 / 빈 줄은 최대 1줄
- `summary_trust`:

| 조건 | 결과 |
|---|---|
| 정리 후 비었거나 정보 없는 문구(`-`, `책 정보가 없습니다` 등) | `summary: null`, `summary_trust: null` |
| `books.link`가 검색 결과 주소(`search.shopping.naver.com`) | `"suspect"` — 자동 검색으로 채워진 소개라 다른 책의 것일 수 있음 |
| 그 외 | `"ok"` |

- 앱 표시: `ok` 그대로 / `suspect` 접어 두고 "다른 책 소개일 수 있습니다" 안내 후 펼치기 / `null` 숨김
</details>
