# 이미지 부하 대응을 위한 CloudFront + S3 도입 작업 문서

## 1. 작업 목적

기존 구조에서는 이미지 업로드와 조회의 파일 본문이 모두 Spring 백엔드를 통과했다. 사용자가 몰리면 이미지 크기만큼 백엔드 네트워크 대역폭을 사용하고, multipart 처리와 파일 스트리밍이 제한된 Tomcat 연결과 스레드를 오래 점유한다. 현재 서버 설정은 최대 스레드 10개, 최대 연결 50개이므로 이미지 전송 시간이 길어질수록 채팅·인증 같은 작은 API까지 대기할 가능성이 높다.

이번 작업의 목적은 인증과 권한 판단은 백엔드에 남기되, 이미지 본문 전송은 S3와 CloudFront로 분리하는 것이다. 기존 API는 유지해 기능 플래그만으로 이전 경로로 복귀할 수 있게 했다.

## 2. 변경 전후 데이터 흐름

### 변경 전

```text
업로드: 브라우저 → Spring/Tomcat → 로컬 저장소
조회:   브라우저 ← Spring/Tomcat ← 로컬 저장소
```

한 이미지 업로드마다 백엔드는 전체 요청 본문을 받고 저장소로 다시 전달한다. 조회 시에도 저장소에서 읽은 전체 응답 본문을 사용자에게 전송한다. 동시 이미지 요청이 증가하면 다음 자원이 함께 증가한다.

- 백엔드 ingress/egress 네트워크 사용량
- multipart 파싱 및 스트리밍 연결 유지 시간
- Tomcat active thread와 connection 점유
- 로컬 디스크 I/O
- 애플리케이션 인스턴스별 파일 일관성 관리 비용

### 변경 후

```text
업로드 제어: 브라우저 → 백엔드 → presigned PUT 정보
이미지 본문: 브라우저 ─────────→ S3
업로드 확정: 브라우저 → 백엔드 → S3 HeadObject + Mongo 메타데이터

채팅 조회 권한: 브라우저 → 백엔드 → CloudFront Signed URL
이미지 본문:    브라우저 ← CloudFront ← S3

프로필 조회 권한: 브라우저 → 백엔드 → CloudFront Signed URL
프로필 본문:      브라우저 ← CloudFront ← S3
```

백엔드는 presign, 완료 검증, 권한 확인처럼 크기가 작은 제어 요청만 처리한다. 이미지 바이트는 백엔드 ingress/egress를 통과하지 않는다.

## 3. 구현 과정과 부하 대응 효과

### 저장소 분리

- `StoragePort`의 기존 계약을 구현하는 `S3Storage`를 추가했다.
- 로컬 저장소 구현은 남겨 개발 환경과 fallback 경로를 보존했다.
- 프로필은 `profiles/{userId}/{UUID}`, 채팅은 `chat/images/{userId}/{UUID}`로 객체 키를 분리했다.
- UUID 기반 불변 키를 사용해 동시 업로드 충돌과 CloudFront의 덮어쓰기 캐시 문제를 제거했다.

이로써 애플리케이션 인스턴스가 늘어나도 인스턴스 로컬 파일을 동기화할 필요가 없다. 서버 증설과 교체도 이미지 파일의 위치에 영향을 받지 않는다.

### 업로드 본문 오프로딩

- 백엔드는 파일 메타데이터를 검증하고 10분짜리 S3 presigned PUT URL만 발급한다.
- 브라우저가 S3로 직접 PUT한 뒤 complete API를 호출한다.
- complete는 S3 `HeadObject`로 실제 크기와 Content-Type을 확인한 후에만 DB 파일 레코드를 만든다.
- 업로드 세션은 `PENDING`, `COMPLETED`, `FAILED`, `EXPIRED` 상태로 관리한다.
- object key unique index와 file의 `directUploadId` unique index로 중복 complete를 방지한다.

대용량 multipart 요청이 Tomcat 연결을 점유하지 않기 때문에 이미지 업로드가 몰려도 채팅 메시지, 방 목록, 로그인 요청이 같은 스레드 풀에서 밀리는 현상이 줄어든다. 업로드 실패도 S3 전송 구간에 격리되어 백엔드 메모리와 로컬 디스크에 불완전 파일을 남기지 않는다.

### 조회 트래픽 오프로딩

- 프로필 이미지는 인증된 사용자에게 최대 50개씩 5분짜리 Signed URL을 일괄 발급한다.
- 채팅 이미지는 파일→메시지→채팅방 참가자 순서로 권한을 확인한 뒤 5분짜리 Signed URL을 발급한다.
- 최대 50개 파일의 URL을 한 API 요청으로 처리한다.
- 프런트는 같은 렌더 사이클의 URL 요청을 묶고 만료 전까지 메모리 캐시한다.
- 기존 `/view`와 `/download`도 S3 모드에서는 권한 확인 후 CloudFront로 리다이렉트한다.

반복 조회는 CloudFront 엣지 캐시가 처리한다. 백엔드는 이미지 크기와 무관한 권한 요청만 처리하며, 한 화면의 여러 이미지도 파일별 API 호출 대신 일괄 요청으로 줄일 수 있다.

### 장애 격리와 복구

- 프런트와 백엔드에 각각 기능 플래그를 두어 신규 경로를 단계적으로 활성화한다.
- 기존 업로드 API를 제거하지 않아 신규 경로 장애 시 프런트 플래그 OFF 빌드로 복귀할 수 있다.
- complete는 멱등하므로 S3 업로드 이후 응답이 유실돼도 이미지 본문을 재전송하지 않고 확정 요청만 반복한다.
- presign 만료 또는 메타데이터 불일치 객체는 실패 처리하고 삭제한다.
- 남은 미완료 객체는 S3 Lifecycle로 정리한다.
- 부분 성공 access URL 응답으로 한 이미지의 권한·스토리지 오류가 전체 메시지 목록 렌더링을 막지 않는다.

## 4. 부하 발생 시 운영이 쉬워진 지점

| 상황 | 기존 대응 | 변경 후 대응 |
|---|---|---|
| 이미지 업로드 급증 | 백엔드 스레드·네트워크·디스크를 함께 증설 | S3가 본문을 수신하며 백엔드는 presign/complete만 처리 |
| 동일 이미지 반복 조회 | 매번 백엔드가 파일을 읽고 전송 | CloudFront 엣지 캐시가 응답 |
| 백엔드 인스턴스 증설 | 로컬 파일 공유 또는 동기화 필요 | 모든 인스턴스가 동일 S3 객체 메타데이터만 참조 |
| 일부 이미지 업로드 실패 | multipart 재전송과 서버 임시 상태 점검 | 실패 객체 격리, 새 presign 또는 complete 재시도 |
| 비공개 이미지 요청 증가 | 백엔드가 권한 확인과 본문 전송을 모두 수행 | 일괄 권한 확인 후 CloudFront가 본문 전송 |
| 신규 저장 경로 장애 | 코드 롤백 또는 파일 경로 복구 | 기능 플래그 OFF로 기존 API 경로 복귀 |
| 프로필 이미지 교체 | 서버 파일과 캐시를 수동 확인 | S3 삭제와 CloudFront invalidation 수행 |

핵심 변화는 백엔드 스케일 기준이 이미지 전송량에서 제어 요청량으로 바뀐 것이다. 이미지 크기가 커져도 백엔드가 처리하는 요청 본문과 응답 본문은 거의 증가하지 않는다.

## 5. 관측과 장애 판단

다음 애플리케이션 지표를 추가했다.

- `direct_image_upload_presign_total{purpose,result}`
- `direct_image_upload_complete_total{purpose,result}`
- `direct_image_storage_errors_total{operation}`

기존 Micrometer HTTP/Tomcat 지표와 함께 다음 순서로 판단한다.

1. presign 실패 증가: 인증, 입력 검증, IAM 자격 증명 상태를 확인한다.
2. S3 PUT 실패 증가: 브라우저 Network 응답, S3 CORS, URL 만료, Content-Type 일치를 확인한다.
3. complete 실패 증가: S3 객체 존재 여부와 `HeadObject` 크기·Content-Type을 확인한다.
4. Signed URL 실패 증가: CloudFront key pair, private key mount, 배포 도메인과 `chat/*` 서명 요구 동작을 확인한다.
5. CloudFront 5xx 증가: OAC와 S3 origin policy를 확인한다.
6. 백엔드 active thread가 계속 높음: 이미지 외 API, DB, Redis, Socket.IO 부하를 별도로 분석한다. 이미지 본문 트래픽은 원인이 아니어야 한다.

## 6. 부하 검증 방법

이번 구현 검증에서는 실제 AWS 인프라가 없어 개선 수치를 측정하지 않았다. 운영 전에는 동일 이미지 크기와 사용자 패턴으로 기존 경로와 신규 경로를 각각 시험해야 한다.

### 비교 시나리오

- 플래그 OFF: 기존 `/api/files/upload`, `/view` 경유
- 플래그 ON: presign, S3 PUT, complete, CloudFront 조회
- 동일한 동시 사용자, 요청률, 이미지 크기, 시험 시간 사용

### 비교 지표

- 백엔드 ingress/egress bytes
- Tomcat active/current/max threads와 connections
- API p50/p95/p99 latency
- CPU, heap, GC pause
- presign/complete 오류율
- S3 PUT latency와 오류율
- CloudFront cache hit ratio, origin latency, 4xx/5xx
- 채팅 메시지와 방 목록 API의 p95 변화

### 완료 기준

- 신규 업로드에서 이미지 본문이 백엔드 네트워크를 통과하지 않는다.
- 이미지 부하 중에도 채팅·인증 API 지연과 오류율이 기준선 범위에 머문다.
- CloudFront cache hit 이후 백엔드 파일 조회 트래픽이 발생하지 않는다.
- 비참가자는 채팅 이미지 Signed URL을 발급받지 못한다.
- 플래그 OFF에서 기존 기능이 동일하게 동작한다.

## 7. 운영 전 필수 확인

- S3 Block Public Access 활성화 및 CloudFront OAC만 origin 접근 허용
- `profiles/*`와 `chat/*` 모두 Trusted Key Group 기반 Signed URL 필수
- S3 CORS에 실제 프런트 Origin, PUT, Content-Type만 허용
- IAM Role 최소 권한 적용 및 장기 access key 미사용
- CloudFront private key를 비밀 경로로 마운트하고 로그에서 URL 쿼리 제거
- `direct_uploads.expiresAt` TTL 및 object key unique index 생성 확인
- S3 미완료 객체 Lifecycle 정책 확인
- 백엔드 플래그 활성화 후 프런트 빌드 플래그 활성화
