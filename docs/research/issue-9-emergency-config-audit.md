# Issue #9: emergency-optimization 연결성 audit

작성일: 2026-08-11 (Asia/Seoul)

## 결론

비교 프로젝트의 `config/emergency-optimization.js`는 **파일로 존재하고 Node.js 모듈로 로드되지만, 접근 가능한 소스에서 서버 실행 경로로 import/require되는 근거가 없다.** `backend/server.js`는 Socket.IO와 MongoDB 옵션을 파일과 별도로 하드코딩하고, Redis·캐시도 자체 설정을 사용한다.

- `파일 존재`: 확인
- `import/require 연결`: 접근 가능한 소스에서 미발견
- `실제 서버 실행 관찰`: 확인 불가
- `설정 적용 전후 실측 효과`: 근거 없음
- 현재 BootcampChat으로 복사할 항목: 없음

따라서 이 파일을 최적화 사례나 현재 서버에 적용된 설정으로 소개하지 않는다. 특히 `writeConcern: { w: 0, j: false }`는 적용하지 않았고, 데이터 유실 위험 후보로만 기록한다.

## 조사 범위와 출처

### Issue 원문

- GitHub Issue [#9](https://github.com/KTB4-loadtest-9/ktb-BootcampChat/issues/9)
- 상태: `OPEN`, 댓글 0개, GitHub 라벨 없음
- 이 문서의 기준 코드: `origin/main` `2058a6a9c1c0154da96fbba9e48542d892522693`

### 비교 프로젝트

- 접근 경로: `/Users/connor/IdeaProjects/ktb-load-testing-main`
- 확인한 원본 파일: `config/emergency-optimization.js`
- 제한: 이 디렉터리에는 `.git`과 선언된 remote가 없어 commit, branch, remote provenance를 확인할 수 없다. README에도 실제 repository URL이 없고 `[repository-url]` placeholder만 있다. 따라서 아래 결과는 **접근 가능한 local working tree snapshot에 한정**하며, 배포된 원본이나 다른 commit의 내용까지 보장하지 않는다.

### 현재 BootcampChat

- `git grep`으로 `emergency-optimization`, `MONGO_EMERGENCY`, `REDIS_EMERGENCY`, `writeConcern`, `w: 0`, `j: false`를 `HEAD`에서 검색했다.
- 현재 저장소는 Java/Spring Boot 설정인 [`CacheConfig.java`](../../apps/backend/src/main/java/com/ktb/chatapp/config/CacheConfig.java), [`MongoConfig.java`](../../apps/backend/src/main/java/com/ktb/chatapp/config/MongoConfig.java), [`SocketIOConfig.java`](../../apps/backend/src/main/java/com/ktb/chatapp/config/SocketIOConfig.java)를 사용한다.

## 연결성 조사

### import/require 검색

다음 검색을 비교 프로젝트 전체에서 실행했다. `node_modules`, Playwright report, binary fixture는 제외했다.

```bash
rg -n --hidden \
  -g '!**/.git/**' -g '!**/node_modules/**' -g '!**/playwright-report/**' \
  -g '!*.png' -g '!*.pdf' -g '!*.txt' \
  'emergency-optimization|emergencyOptimization' \
  /Users/connor/IdeaProjects/ktb-load-testing-main
```

결과는 설정 파일의 주석/선언 자체뿐이었다. 별도의 `import`, `require`, 동적 로딩 경로, export 소비 지점은 발견되지 않았다. export된 이름도 파일 내부의 선언과 `module.exports` 외에는 검색되지 않았다.

정적 검색으로 확인할 수 없는 동적 `require()` 조합, 생성된 산출물, 배포 환경의 다른 snapshot은 unverifiable이다.

### 서버 entry/build/deploy/test 경로

| 경로 | 1차 근거 | `emergency-optimization.js` 연결 여부 |
| --- | --- | --- |
| 서버 entry | `backend/package.json:5-8`의 `main`, `start`, `dev`가 모두 `server.js`를 가리킴 | 없음 |
| 실제 Node 서버 | `backend/server.js:1-9`의 require 목록에 해당 파일이 없음 | 없음 |
| Socket.IO runtime | `backend/server.js:84-94`에서 옵션 객체를 직접 생성하고 `./sockets/chat`을 require함 | emergency 설정 미사용 |
| Mongo runtime | `backend/server.js:119-127`의 `mongoose.connect()`에 pool/timeout 값을 직접 전달함 | emergency 설정 미사용 |
| Docker deploy entry | `docker-compose.yml:5-14`가 `./backend` build context로 `backend/Dockerfile`을 빌드하고, `backend/Dockerfile:27`이 `node server.js`를 실행함 | root `config/`는 build context 밖이라 이 경로의 이미지에도 포함되지 않으며 runtime import도 없음 |
| root start/build | root `package.json:12-16`의 build는 frontend만, start는 backend/frontend 각 entry를 실행함 | 없음 |
| test entry | root `package.json:16`의 test는 `e2e`의 npm test이고, 전체 검색에서 해당 설정 소비 지점 없음 | 없음 |

`backend/Dockerfile:14`의 `COPY . .`는 `docker-compose.yml:7`의 `./backend` context만 복사한다. 따라서 root `config/emergency-optimization.js`가 이미지에 포함된다는 근거도 없고, Node runtime이 모듈을 읽었다는 증거도 없다.

## 설정별 기본값·활성화·소비 위치

| 영역 | emergency 파일의 값 | 활성화 조건 | 실제 소비 위치 | 판정 |
| --- | --- | --- | --- | --- |
| Socket.IO | `pingTimeout=1800000`, `pingInterval=900000`, `maxHttpBufferSize=2048`, websocket only, compression off 등 (`config/emergency-optimization.js:3-22`) | 없음 | `backend/server.js:84-94`의 별도 inline 옵션: timeout `45000/20000`, buffer `512000`, `allowEIO3=true`, compression `true` | dead config. 일부 값이 비슷해도 동일 객체가 아님 |
| 메모리·캐시·정리 | `BATCH_SIZE=1`, `MAX_RETRIES=0`, TTL `10/5/5`, 강제 GC 확률 등 (`:24-40`) | 없음 | `backend/services/cacheService.js:5-13`의 TTL은 `900/600/300/300/300`이고 emergency export를 사용하지 않음 | dead config. 메시지 batch/GC 소비 지점 없음 |
| 임계점 | `DISABLE_*`, `EMERGENCY_MODE`, `SURVIVAL_MODE`, `SHUTDOWN_MODE` (`:42-53`) | 없음 | 이름과 값의 다른 소비 지점 없음 | dead config. 기능 차단/신규 연결 차단은 관찰되지 않음 |
| MongoDB | `maxPoolSize=1`, 짧은 timeout, `retryWrites=false`, `w:0`, `j:false` 등 (`:55-70`) | 없음 | `backend/server.js:119-127`의 pool 설정은 `100/30/20` 등 별도 값이며 write concern을 전달하지 않음 | dead config. 데이터 유실 위험 설정은 적용 금지 |
| Redis | timeout/retry/cluster/queue/lazy connect 값 (`:72-89`) | 없음 | `backend/utils/redisClient.js:157-239`, `:285-381`에서 cluster 여부와 host 설정에 따라 별도 `ioredis` 객체를 생성함 | dead config. 실제 Redis 분기와 연결되지 않음 |
| Express | body limit `512kb`, compression/etag/lastModified off (`:91-98`) | 없음 | `backend/server.js:50-59`는 `express.json()`과 `urlencoded()`만 직접 호출함 | dead config. 설정 적용 관찰 없음 |

비교 프로젝트의 실제 cache TTL과 Redis 옵션이 존재한다는 사실은 `emergency-optimization.js`의 값이 소비된다는 뜻이 아니다. 두 설정군은 서로 다른 코드 경로다.

## 실행·smoke 근거

설정 파일 자체를 서버에 적용하지 않고 다음 안전한 모듈 수준 확인만 실행했다.

```bash
cd /Users/connor/IdeaProjects/ktb-load-testing-main
node --check config/emergency-optimization.js
node -e "const config=require('./config/emergency-optimization'); console.log(JSON.stringify({exports:Object.keys(config), mongoWriteConcern:config.MONGO_EMERGENCY_CONFIG.writeConcern, socketTransports:config.EMERGENCY_SOCKET_CONFIG.transports}, null, 2))"
```

결과:

```json
{
  "exports": [
    "EMERGENCY_SOCKET_CONFIG",
    "MEMORY_EMERGENCY_CONFIG",
    "EMERGENCY_THRESHOLDS",
    "MONGO_EMERGENCY_CONFIG",
    "REDIS_EMERGENCY_CONFIG",
    "EXPRESS_EMERGENCY_CONFIG"
  ],
  "mongoWriteConcern": { "w": 0, "j": false },
  "socketTransports": ["websocket"]
}
```

이 결과는 파일의 문법과 export가 유효하다는 smoke 근거일 뿐, 서버가 이 설정을 import하거나 적용했다는 근거가 아니다. `backend/server.js:70-77`의 `/health` 응답도 `status`, timestamp, env만 반환하며 emergency 설정 적용 여부를 노출하지 않는다. 따라서 emergency 설정의 runtime log, health evidence, integration test evidence는 **미확인**이다.

## 데이터 안정성 및 성능 판단

- `MONGO_EMERGENCY_CONFIG.writeConcern`의 `w: 0`, `j: false`는 쓰기 확인·저널링을 낮추는 위험한 후보다. 현재 서버와 이 audit 실행에는 적용하지 않았다.
- `retryWrites: false`, `MAX_RETRIES: 0`, Mongo pool 1, 짧은 timeout, Socket.IO 호환성/압축 비활성화도 장애·데이터 안정성·기능 호환성 검토 없이 복사하지 않는다.
- 설정 적용 전후의 latency, throughput, error rate, data-loss 검증 수치는 없다. 따라서 성능 개선 사례로 표현하지 않는다.
- 현재 BootcampChat에는 emergency 파일을 추가하지 않고, 기존 Java 설정 경로를 유지한다. [`CacheConfig.java`](../../apps/backend/src/main/java/com/ktb/chatapp/config/CacheConfig.java#L24-L57)는 Redis cache TTL을, [`MongoConfig.java`](../../apps/backend/src/main/java/com/ktb/chatapp/config/MongoConfig.java#L12-L22)는 Mongo index를, [`SocketIOConfig.java`](../../apps/backend/src/main/java/com/ktb/chatapp/config/SocketIOConfig.java#L26-L78)는 Socket.IO server bean을 각각 소유한다.

## Acceptance criteria 체크

- [x] 설정 파일의 import/require 경로를 비교 프로젝트 전체에서 검색했다.
- [x] 파일 존재, runtime import, 실행 관찰, 실측 효과를 분리했다.
- [x] 설정별 값·활성화 조건·소비 위치를 표로 정리했다.
- [x] `w:0`, `j:false`를 적용하지 않고 위험을 기록했다.
- [x] 모듈 syntax/export smoke를 실행했다. 서버 runtime 적용 smoke는 연결 부재로 미확인이라고 표시했다.
- [x] before/after 성능 수치가 없으므로 개선 사례로 표현하지 않았다.
- [x] 현재 BootcampChat에 복사할 항목과 복사하지 않을 항목을 분리했다.

## 제한

1. 비교 프로젝트의 `.git`, remote, 원본 commit이 없어 historical/original source와 배포본을 대조할 수 없다.
2. 원본 repository를 식별할 수 있는 URL이나 deployment artifact를 찾지 못했다. 따라서 이 결과는 위 local snapshot의 정적 소스 근거다.
3. 실행 중인 비교 서버, 로그, health endpoint, production metrics에는 접근하지 않았다. `emergency-optimization.js`가 다른 환경에서 동적으로 로드될 가능성은 unverifiable이다.
4. 현재 BootcampChat의 검색 기준은 `origin/main` commit `2058a6a`이며, 배포 환경이 동일한지는 이 문서 범위에서 확인하지 않았다.
