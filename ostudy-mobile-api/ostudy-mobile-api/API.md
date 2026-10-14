# Ostudy Mobile API (ostudy/v1)
Base: https://YOUR-SITE/wp-json/ostudy/v1

Auth: POST /auth/login {login,password,device_name} -> {user, username, app_password}. Then send HTTP Basic "username:app_password" on every call. POST /auth/logout revokes it.

Public: GET /config, /filters, /courses, /courses/{id}, /tutors, /tutors/{id}; POST /auth/register, /auth/forgot
Student: GET/POST /me, POST /me/avatar, GET /me/courses, GET /lessons/{id}, POST /lessons/{id}/complete, POST /courses/{id}/enroll (free),
  GET /checkout/preview?course_id=, POST /checkout, GET /orders, GET /orders/{id}, POST /orders/{id}/proof, GET /orders/{id}/receipt, POST /auth/web-session
Tutor: GET /instructor/overview, GET /instructor/payments, POST /instructor/payments/{id}/approve|reject, GET/POST /instructor/payment-methods, DELETE /instructor/payment-methods/{id}, GET /instructor/payment-methods/types
Admin: GET /diagnostics (reports which plugin integrations were found)

Not yet: private sessions, messages (Better Messages), push registration (reuse myapp-mobile-bridge register-device).
