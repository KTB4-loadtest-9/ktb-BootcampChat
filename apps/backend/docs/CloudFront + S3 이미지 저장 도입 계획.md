# CloudFront + S3 이미지 저장 도입 계획

## 목표

- 채팅과 프로필 이미지 본문을 백엔드가 중계하지 않고 브라우저가 S3에 직접 업로드한다.
- S3는 완전 비공개로 유지하고 모든 이미지 조회는 만료형 CloudFront Signed URL로 제한한다.
- DB에는 공개 URL이나 서명 URL이 아닌 object key만 저장한다.
- 기존 multipart 업로드 API는 PDF와 기존 클라이언트 호환성을 위해 유지한다.

## 데이터 흐름

### 업로드

1. 인증된 프런트가 채팅 또는 프로필 presign API에 파일명, MIME, 크기를 전달한다.
2. 백엔드는 서버 생성 object key와 10분짜리 S3 Presigned PUT URL을 반환한다.
3. 브라우저가 이미지 본문을 S3에 직접 PUT 한다.
4. 채팅 이미지는 JSON `POST /api/files/upload`, 프로필 이미지는 전용 complete API가 `HeadObject`로 크기와 Content-Type을 확인하고 DB 메타데이터를 확정한다.

채팅 complete 요청은 기존 E2E가 관찰하는 URI를 유지한다.

```json
POST /api/files/upload
{"uploadId":"...","uploadType":"PRESIGNED_CHAT_IMAGE"}
```

- JSON 계약은 이미지 본문을 받지 않고 S3 검증과 `File` 메타데이터 확정만 수행한다.
- 기존 multipart `/api/files/upload`는 PDF와 기존 클라이언트 호환성을 위해 계속 유지한다.
- 기존 전용 `/api/files/chat-images/{uploadId}/complete`도 호환성을 위해 유지한다.

### 조회

- 채팅: `POST /api/files/chat-images/access-urls`가 방 참가 권한을 검사하고 최대 50개 Signed URL을 반환한다.
- 프로필: `POST /api/users/profile-images/access-urls`가 인증된 요청에 최대 50개 Signed URL을 반환한다.
- 두 URL 모두 기본 TTL은 5분이며 프런트는 만료 30초 전까지만 메모리에 캐시한다.
- `/api/files/profiles/**`는 더 이상 익명 접근을 허용하지 않는다. 로컬 모드에서만 인증 토큰이 포함된 fallback URL로 사용한다.

## 백엔드 변경

- `S3Storage`가 S3 put/open/delete와 CloudFront 오프로딩을 담당한다.
- `direct_uploads` 컬렉션에서 PENDING/COMPLETED/FAILED/EXPIRED 상태와 TTL을 관리한다.
- 프로필 complete는 `User.profileImage`에 `profiles/{userId}/{UUID}.{ext}` key만 저장한다.
- 과거 공개 절대 URL 또는 `/api/files/...` 저장값은 access 서비스에서 key로 정규화해 전환 기간에도 읽을 수 있게 한다.
- 프로필 교체·삭제 시 S3 객체 삭제와 CloudFront invalidation을 수행한다.

## 프런트 변경

- 이미지는 별도 기능 플래그 없이 항상 presign → PUT → complete 흐름을 사용한다.
- PDF는 기존 백엔드 업로드 API를 유지한다.
- `profileImageService`가 아바타 URL 요청을 렌더 사이클 단위로 묶고 메모리에 캐시한다.
- `CustomAvatar`는 사용자 응답의 profileImage 값을 `<img src>`로 직접 사용하지 않는다.
- 업로드/조회 URL, JWT, sessionId는 영구 저장하거나 로그에 남기지 않는다.

## 보안·인프라 요구사항

- S3 Block Public Access를 활성화하고 CloudFront OAC만 `GetObject`를 허용한다.
- CloudFront의 `profiles/*`, `chat/*` behavior 모두 Trusted Key Group 서명을 요구한다.
- S3 CORS는 실제 프런트 Origin, PUT, Content-Type만 허용한다.
- 애플리케이션은 EC2/ECS IAM Role로 S3와 CloudFront API를 호출하며 장기 access key를 배포 파일에 두지 않는다.
- CloudFront private key는 파일형 secret으로 마운트하고 애플리케이션 로그에 노출하지 않는다.

## 배포 및 롤백

1. 비공개 S3, OAC, Signed URL behavior와 IAM을 먼저 배포한다.
2. 아래 S3·CloudFront 환경변수와 IAM Role을 적용해 백엔드를 배포한다.
3. 프런트를 배포하면 이미지 요청은 항상 직접 업로드 흐름을 사용한다.
4. 테스트 환경에서 채팅·프로필 업로드와 조회를 검증한 뒤 운영에 반영한다.
5. 장애 시에는 이전 애플리케이션 버전으로 롤백한다. 기능 플래그 기반 전환은 제공하지 않는다.

## 클라우드 배포 담당자 전달사항

백엔드 런타임에 다음 환경변수를 주입한다.

```env
FILE_STORAGE_TYPE=s3
AWS_REGION=ap-northeast-2
AWS_S3_BUCKET=<비공개 이미지 버킷>
AWS_CLOUDFRONT_DOMAIN=<CloudFront 배포 도메인>
AWS_CLOUDFRONT_DISTRIBUTION_ID=<배포 ID>
AWS_CLOUDFRONT_KEY_PAIR_ID=<Trusted Key Group public key ID>
AWS_CLOUDFRONT_PRIVATE_KEY_PATH=<컨테이너/EC2 내부 private key 절대 경로>
DIRECT_UPLOAD_PRESIGN_TTL=PT10M
CLOUDFRONT_SIGNED_URL_TTL=PT5M
CORS_ALLOWED_ORIGINS=<실제 프런트 Origin>
```

프런트는 빌드 시 다음 변수를 사용한다.

```env
NEXT_PUBLIC_API_URL=<외부 백엔드 API URL>
NEXT_PUBLIC_SOCKET_URL=<외부 Socket.IO URL>
```

- API와 Socket URL 같은 `NEXT_PUBLIC_*` 값은 빌드 시 고정되므로 값을 넣은 뒤 프런트를 빌드한다.
- CloudFront private key 파일은 저장소나 일반 환경변수에 넣지 않고 Secrets Manager/SSM 또는 배포 플랫폼 secret volume으로 마운트한다.
- EC2/ECS Role에는 대상 prefix의 `s3:PutObject`, `s3:GetObject`, `s3:HeadObject`, `s3:DeleteObject`와 해당 배포의 `cloudfront:CreateInvalidation`만 부여한다.
- S3 bucket policy는 CloudFront OAC의 `GetObject`만 허용하고 익명 principal 및 public ACL을 금지한다.
- CloudFront의 `profiles/*`, `chat/*` behavior에 같은 Trusted Key Group을 연결하고 unsigned 요청이 403인지 배포 전에 확인한다.
- S3 CORS에는 프런트 Origin의 `PUT`, `Content-Type`만 허용한다.
- 기존 공개 프로필 URL이 DB에 남아 있어도 애플리케이션이 key로 정규화하지만, 기존 CloudFront 공개 behavior는 별도로 제거해야 한다.

## 검증 기준

- 이미지 PUT과 GET 본문이 백엔드 ingress/egress를 통과하지 않는다.
- 익명 요청은 프로필 이미지 경로와 access API에서 401을 받는다.
- 채팅방 비참가자는 채팅 이미지 Signed URL을 받지 못한다.
- Signed URL 만료 후 프런트가 새 URL을 발급받는다.
- 프로필 교체·삭제 후 이전 URL로 객체를 조회할 수 없다.
- PDF와 기존 multipart 클라이언트가 기존 `/api/files/upload` 계약을 계속 사용할 수 있다.
