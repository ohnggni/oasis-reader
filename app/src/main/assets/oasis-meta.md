# oasis-meta — 북오아시스 옆에 띄우는 메타 API

> **북오아시스(BookOasis) 전용 안내입니다.** OG Reader의 정리된 카탈로그를 쓰려면 북오아시스 서버 옆에
> oasis-meta를 직접 띄워야 합니다. 북오아시스 공식 기능이 아니며, 북오아시스 개발자와 무관한 비공식 보조 도구입니다.
>
> ⚠️ oasis-meta는 북오아시스의 **라이브러리별 사용자 권한을 적용하지 않습니다.** 여러 사람이 쓰는 서버라면
> 볼 수 없어야 할 목록이 보일 수 있으니, 혼자 쓰는 서버에서만 사용하십시오.

OG Reader의 **정리된 카탈로그**(시리즈별 표지 목록, 정리된 제목, 실제 날짜 정렬, 책 상세·줄거리)는
북오아시스 OPDS만으로는 만들 수 없습니다. 그래서 북오아시스 DB(MariaDB)를 **읽기 전용**으로 조회하는
작은 HTTP API("oasis-meta")를 옆에 띄워서 보완합니다.

- 파일·구글 드라이브에는 접근하지 않습니다 (DB만 읽음) → 드라이브 API 사용량 0
- 책 파일 다운로드와 표지 이미지는 여전히 **북오아시스**에서 받습니다
- 없어도 앱은 OPDS 폴더 목록으로 동작합니다. 필요한 사람만 만들면 됩니다

> 이 문서는 API **사양**과 구현 시 주의점입니다. 언어·프레임워크는 자유입니다 (작성자는 Python + gunicorn 컨테이너).
> 앱은 아래 필드 이름·형식만 맞으면 동작합니다.

---

## 1. 왜 필요한가 (북오아시스 OPDS의 한계)

| 문제 | 원인 | oasis-meta |
|---|---|---|
| 제목이 `[만화] 1` | OPDS가 DB `title`을 그대로 씀. 폴더의 `kavita.yaml`이 오염되면 title이 권 번호 | `display_title` (파일명 기반) |
| 시리즈명/저자 없음 | OPDS 항목에 출력 안 됨 | `series_name` |
| `<updated>`가 요청 시각 | `datetime.now()`로 채워짐 | `file_mtime` |
| 표지 경로 없음 | 썸네일 파일명이 경로 해시라 id로 추측 불가 | `cover_url` |
| 요약이 엉뚱한 책 | `kavita.yaml`이 검색 후보 리스트로 오염 → 첫 후보 적용 | `summary` + `summary_trust` |

### 날짜 주의
- DB `created_at`(북오아시스가 처음 본 시각)은 최초 전체 스캔일에 대부분 몰려 있어 정렬에 못 씁니다.
- **`file_mtime`**(파일 수정 시각)을 날짜 정렬·표시에 씁니다.

---

## 2. 접속 규칙

- **인증:** HTTP Basic — **북오아시스 계정 그대로** (OPDS와 같은 아이디/비밀번호)
  - `users` 테이블의 `password_hash`를 북오아시스 로그인과 같은 방식으로 검증 (북오아시스 소스의 로그인 코드 참고)
  - 매 요청 해시 검증은 느리니 결과를 몇 분 캐시하길 권장
  - 실패 → `401` + `WWW-Authenticate: Basic realm="oasis-meta"`
- **DB 선택:** 모든 인증 엔드포인트에 쿼리 `db=general`(기본) 또는 `db=adult`
  - `adult`는 `role=admin` 계정만, 아니면 `403 {"error":"forbidden"}`
- **응답:** JSON, UTF-8 (한글 그대로, `ensure_ascii=False`)
- **날짜:** `file_mtime` / `latest_mtime` / `first_mtime` = UTC ISO 8601 (`2022-05-20T01:45:53Z`)
  `created_at` / `latest_added` = `"YYYY-MM-DD HH:MM:SS"` 문자열
- **에러:** 잘못된 숫자 파라미터 → `400 {"error":"bad parameter: ..."}`, 없는 책 → `404 {"error":"not found"}`
- **페이지 응답 공통:** `{"items": [...], "page": 1, "limit": 100, "has_more": true}`

---

## 3. 엔드포인트

### `GET /health` (인증 없음)
→ `{"ok": true}`

### `GET /libraries`
```json
{"items": [{"id": 23, "name": "만화"}]}
```

### `GET /series`
시리즈 = `library_id` + `series_name`(책 파일의 **부모 폴더명**).

| 파라미터 | 설명 | 기본 |
|---|---|---|
| `library_id` | 라이브러리 필터 | 전체 |
| `q` | 시리즈명 검색 (공백으로 나눈 단어 AND, 최대 10개) | — |
| `format` | 파일 형식 필터, 콤마 구분 (`cbz,zip`) | 전체 |
| `sort` | `mtime_desc` `mtime_asc` `added_desc` `added_asc` `name` | `mtime_desc` |
| `page` / `limit` | 1부터 / 1~500 | 1 / 100 |

```json
{
  "items": [{
    "library_id": 28,
    "series_name": "환생영애는 정령에게 사랑받아 최강입니다",
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
| `latest_mtime` / `first_mtime` | 시리즈 안 파일 mtime 최대/최소 — **앱의 날짜 정렬·표시** |
| `latest_added` | 북오아시스 추가일 최대값 (참고용) |
| `first_book_id` | 시리즈 안 가장 작은 id (대표 권) |
| `cover_url` | 대표 권 표지 경로 (아래 3-1). 없으면 `null` |

### `GET /books`
권 단위 목록.

| 파라미터 | 설명 | 기본 |
|---|---|---|
| `library_id` | 라이브러리 필터 | 전체 |
| `series` | 시리즈명 **정확히 일치** (`/series`의 `series_name` 그대로) | — |
| `q` | 제목/시리즈명/저자 검색 (단어 AND, 최대 10개) | — |
| `format` | `cbz,zip` 등 | 전체 |
| `sort` | `mtime_desc` `mtime_asc` `added_desc` `added_asc` `title` | `mtime_desc` |
| `page` / `limit` | 위와 같음 (최대 500) | 1 / 100 |

앱은 시리즈 화면에서 `/books?series=…&library_id=…&sort=title&limit=500`을 부르고, 권 순서는 `display_title` 자연 정렬로 다시 맞춥니다.

### `GET /books/{id}`
권 하나 = `/books`의 item + **`summary`, `summary_trust`** (이 두 필드는 목록에는 넣지 않음).

```json
{
  "id": 152396,
  "title": "1",
  "display_title": "앤틱 로맨틱 01권",
  "series_name": "앤틱 로맨틱 [하즈키 카오루]",
  "author": "…",
  "library_id": 23,
  "file_name": "앤틱 로맨틱 01권#188.cbz",
  "file_format": "cbz",
  "file_size": 45180930,
  "total_pages": 188,
  "file_mtime": "2022-05-20T01:45:53Z",
  "created_at": "2026-06-30 05:25:03",
  "cover_url": "/covers/23/book_71127a699bf16807ec6eb54808a4cbcd.webp",
  "download_path": "/opds/download/general/152396",
  "summary": "…",
  "summary_trust": "suspect"
}
```

| 필드 | 의미 |
|---|---|
| `display_title` | **화면 표시용 제목.** 파일명에서 확장자와 끝의 `#숫자`(페이지 수)를 뗀 것 |
| `title` | DB 원본 제목 (오염 가능, 앱은 표시에 안 씀) |
| `author` | DB 원본 (오염 가능, 앱은 "틀릴 수 있음" 표시) |
| `file_format` | `epub` `cbz` `zip` `txt` `pdf` … (앱이 받을 수 있는 형식 판단에 씀) |
| `total_pages` | 0이면 미분석 |
| `download_path` | 북오아시스 기준 경로. 앱이 OPDS 서버 호스트를 붙여 받음 |
| `summary` | `/books/{id}`만. 줄거리 또는 `null` (3-2) |
| `summary_trust` | `/books/{id}`만. `"ok"` \| `"suspect"` \| `null` (3-2) |

---

## 3-1. 표지 (`cover_url`)

- **출처:** DB `books.cover_image` (북오아시스 스캐너가 `"{library_id}/book_{파일경로 md5}.webp"`로 저장)
- **변환** (북오아시스 웹 `static/js/cover_fallback.js`의 `getBookCoverSrc`와 같게):
  앞의 `/`·`covers/`를 떼고 `/covers/`를 붙임 → `23/book_x.webp` → `/covers/23/book_x.webp`.
  `http(s)://`·`/api/`로 시작하면 그대로, 빈 값·확장자 없는 값은 `null`.
- 앱은 **OPDS 서버 호스트 + `cover_url`**로 받습니다. 북오아시스의 `/covers/*`는 인증 없는 공개 경로입니다.
- 북오아시스가 `.webp`를 `Content-Type: image/png`로 보내는 경우가 있는데, 앱은 바이트로 판별하니 문제없습니다.
- `cover_url`이 없으면 앱이 OPDS 검색으로 썸네일을 한 번 찾아보고, 그래도 없으면 제목 표지를 그립니다.

## 3-2. 줄거리 (`summary`, `summary_trust`)

- **출처:** DB `books.summary` (권 단위 컬럼이지만 대부분 폴더 사이드카에서 채워져 사실상 시리즈 단위)
- 그 권의 값만 내려 줍니다. 비어 있어도 다른 권 값으로 채우지 않습니다.
- **텍스트 정리 (서버에서):**
  - 글자 그대로 저장된 `\n`(역슬래시+n), `<br>`, `<p>`, `<div>` → 실제 줄바꿈
  - 그 밖의 **영문 HTML 태그만** 제거, `&amp;` 등 엔티티 복원
  - `<책소개>` 같은 **한글 꺾쇠 텍스트는 보존**
  - 줄 끝 공백 제거, 빈 줄은 최대 1줄
- **`summary_trust` 판정:**

| 순서 | 조건 | 결과 |
|---|---|---|
| 1 | 정리 후 빈 문자열, 또는 정보 없는 문구(`-`, `책 정보가 없습니다`, `본 도서는 책소개가 없습니다`, `등록된 설명이 없습니다`, `상세 설명 없음`) | `summary: null`, `summary_trust: null` |
| 2 | `books.link`에 `search.shopping.naver.com` 포함 | `"suspect"` |
| 3 | 그 외 | `"ok"` |

  **근거:** 북오아시스 kavita.yaml 파서는 `meta:`/`Summary`가 없으면 `search:` 후보 리스트의 첫 항목(네이버 책 검색 결과)을
  그대로 씁니다. 이 경로로 들어온 값만 link가 네이버 쇼핑 주소입니다. 작성자 서버 표본 조사에서 이 그룹은 절반 이상이 다른 책 소개였고,
  나머지 출처(리디, 카카오페이지, 네이버 시리즈, 노벨피아, yes24, 알라딘, link 없음)는 표본 60개 중 오염 0건이었습니다.
- **앱 표시:** `ok` 그대로 / `suspect` 접어두고 "다른 책 소개일 수 있습니다" 경고 후 펼치기 / `null` 숨김

---

## 4. 설치

준비물: 북오아시스가 Docker(MariaDB)로 돌고 있는 서버, `git`, `docker compose`.

```
git clone https://github.com/ohnggni/oasis-meta
cd oasis-meta
bash install.sh
```

스크립트가 북오아시스 DB 컨테이너를 찾고, 할 일을 요약해 보여준 뒤 확인을 받고 진행합니다.
읽기 전용 계정 생성 → 컨테이너 실행 → 상태 확인까지 자동입니다.

그다음 외부에서 쓰려면 리버스 프록시로 `https://meta.<내 도메인>` → `<서버>:5931` 을 연결하고,
OG Reader › 서버 설정 › **메타 API 주소**에 넣은 뒤 연결 확인을 누릅니다.

- 업데이트: `git pull && bash install.sh` (비밀번호가 든 `.env`는 그대로 둡니다)
- 제거: `docker compose down` (DB 계정까지 지우려면 `DROP USER 'oasis_ro'@'%';`)
- 코드와 자세한 설명: https://github.com/ohnggni/oasis-meta (MIT 라이선스)

## 5. 알려진 제약

- 북오아시스의 라이브러리별 사용자 권한은 적용 안 됨 (성인 DB만 admin 제한)
- 데이터는 북오아시스 스캔 결과를 따름. 스캔 안 된 새 파일은 보이지 않습니다
- 오염된 요약은 표시만 구분하고 고치지 않습니다 (원본 kavita.yaml 문제)
