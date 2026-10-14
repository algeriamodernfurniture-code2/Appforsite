<?php
if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

/**
 * Login with WordPress Application Passwords.
 *
 * The app sends username + password ONCE to /auth/login; the server checks them, creates an application
 * password for this phone and returns it. From then on the app sends "Authorization: Basic base64(user:apppass)"
 * and WordPress core authenticates every REST request. Logging out revokes that one application password.
 */
class OSA_Auth {

	public function routes() {
		$open = '__return_true';
		$auth = array( $this, 'require_login' );

		register_rest_route( OSA_NS, '/auth/login', array( 'methods' => 'POST', 'permission_callback' => $open, 'callback' => array( $this, 'login' ) ) );
		register_rest_route( OSA_NS, '/auth/register', array( 'methods' => 'POST', 'permission_callback' => $open, 'callback' => array( $this, 'register' ) ) );
		register_rest_route( OSA_NS, '/auth/forgot', array( 'methods' => 'POST', 'permission_callback' => $open, 'callback' => array( $this, 'forgot' ) ) );
		register_rest_route( OSA_NS, '/auth/logout', array( 'methods' => 'POST', 'permission_callback' => $auth, 'callback' => array( $this, 'logout' ) ) );
		register_rest_route( OSA_NS, '/auth/web-session', array( 'methods' => 'POST', 'permission_callback' => $auth, 'callback' => array( $this, 'web_session' ) ) );
		register_rest_route(
			OSA_NS,
			'/me',
			array(
				array( 'methods' => 'GET', 'permission_callback' => $auth, 'callback' => array( $this, 'me' ) ),
				array( 'methods' => 'POST', 'permission_callback' => $auth, 'callback' => array( $this, 'update_me' ) ),
			)
		);
		register_rest_route( OSA_NS, '/me/avatar', array( 'methods' => 'POST', 'permission_callback' => $auth, 'callback' => array( $this, 'upload_avatar' ) ) );

	}

	public function require_login() {
		return is_user_logged_in() ? true : OSA_Util::error( 'not_logged_in', 'Authentication required.', 401 );
	}

	/* ------------------------------------------------------------------ login */

	public function login( WP_REST_Request $r ) {
		$login  = trim( (string) $r->get_param( 'login' ) );
		$pass   = (string) $r->get_param( 'password' );
		$device = sanitize_text_field( (string) $r->get_param( 'device' ) );

		if ( '' === $login || '' === $pass ) {
			return OSA_Util::error( 'missing_fields', 'Enter your e-mail/username and password.', 400 );
		}
		if ( ! function_exists( 'wp_is_application_passwords_available' ) || ! wp_is_application_passwords_available() ) {
			return OSA_Util::error( 'app_passwords_off', 'Application passwords are disabled on this site (HTTPS required, or a security plugin blocks them).', 503 );
		}

		// Simple brute-force guard: 8 attempts / 10 minutes per IP+login.
		$key      = 'osa_login_' . md5( strtolower( $login ) . '|' . ( $_SERVER['REMOTE_ADDR'] ?? '' ) );
		$attempts = (int) get_transient( $key );
		if ( $attempts >= 8 ) {
			return OSA_Util::error( 'too_many_attempts', 'Too many attempts. Try again in a few minutes.', 429 );
		}

		$user = wp_authenticate( $login, $pass ); // accepts username or e-mail, runs all auth filters (2FA plugins etc.)
		if ( is_wp_error( $user ) ) {
			set_transient( $key, $attempts + 1, 10 * MINUTE_IN_SECONDS );
			return OSA_Util::error( 'invalid_login', 'Wrong e-mail/username or password.', 401 );
		}
		delete_transient( $key );

		if ( ! wp_is_application_passwords_available_for_user( $user ) ) {
			return OSA_Util::error( 'app_passwords_off', 'App login is not available for this account.', 403 );
		}

		$created = WP_Application_Passwords::create_new_application_password(
			$user->ID,
			array( 'name' => 'Ostudy Android' . ( $device ? ' – ' . mb_substr( $device, 0, 40 ) : '' ) . ' – ' . wp_date( 'Y-m-d H:i' ) )
		);
		if ( is_wp_error( $created ) ) {
			return $created;
		}

		return array(
			'username'     => $user->user_login,
			'app_password' => WP_Application_Passwords::chunk_password( $created[0] ),
			'user'         => self::user_payload( $user->ID ),
		);
	}

	public function logout() {
		$item = function_exists( 'rest_get_authenticated_app_password' ) ? rest_get_authenticated_app_password() : null;
		if ( $item ) {
			WP_Application_Passwords::delete_application_password( get_current_user_id(), $item );
		}
		return array( 'success' => true );
	}

	/* ------------------------------------------------------------------ register / forgot */

	public function register( WP_REST_Request $r ) {
		if ( ! get_option( 'users_can_register' ) ) {
			return OSA_Util::error( 'registration_closed', 'Registration is closed.', 403 );
		}
		$name  = sanitize_text_field( (string) $r->get_param( 'name' ) );
		$email = sanitize_email( (string) $r->get_param( 'email' ) );
		$pass  = (string) $r->get_param( 'password' );
		$role  = 'instructor' === $r->get_param( 'role' ) ? 'instructor' : 'student';

		if ( '' === $name || ! is_email( $email ) || strlen( $pass ) < 8 ) {
			return OSA_Util::error( 'invalid_fields', 'Enter your name, a valid e-mail and a password of at least 8 characters.', 400 );
		}
		if ( email_exists( $email ) ) {
			return OSA_Util::error( 'email_exists', 'This e-mail is already registered. Try logging in.', 409 );
		}

		$base = sanitize_user( strstr( $email, '@', true ), true );
		$login = $base ?: 'user';
		$i = 0;
		while ( username_exists( $login ) ) {
			$login = $base . wp_rand( 100, 99999 );
			if ( ++$i > 20 ) {
				return OSA_Util::error( 'username_failed', 'Could not create the account. Try again.', 500 );
			}
		}

		$user_id = wp_insert_user(
			array(
				'user_login'   => $login,
				'user_email'   => $email,
				'user_pass'    => $pass,
				'display_name' => $name,
				'first_name'   => $name,
				'role'         => get_option( 'default_role', 'subscriber' ),
			)
		);
		if ( is_wp_error( $user_id ) ) {
			return OSA_Util::error( 'create_failed', $user_id->get_error_message(), 400 );
		}

		// Mirror what Tutor LMS does for its own registration forms.
		$now = function_exists( 'tutor_time' ) ? tutor_time() : time();
		update_user_meta( $user_id, '_is_tutor_student', $now );
		if ( 'instructor' === $role ) {
			update_user_meta( $user_id, '_is_tutor_instructor', $now );
			update_user_meta( $user_id, '_tutor_instructor_status', 'pending' );
			do_action( 'tutor_new_instructor_after', $user_id );
		}
		do_action( 'user_register_tutor_student', $user_id ); // harmless if nobody listens
		wp_new_user_notification( $user_id, null, 'admin' );

		return array( 'success' => true, 'instructor_pending' => 'instructor' === $role );
	}

	public function forgot( WP_REST_Request $r ) {
		$login = trim( (string) $r->get_param( 'login' ) );
		if ( '' !== $login ) {
			$_POST['user_login'] = $login;
			retrieve_password( $login ); // result deliberately ignored: never reveal whether the account exists
		}
		return array( 'success' => true );
	}

	/* ------------------------------------------------------------------ profile */

	public static function user_payload( $user_id ) {
		$u = get_userdata( $user_id );
		return array(
			'id'            => (int) $user_id,
			'name'          => $u->display_name,
			'first_name'    => $u->first_name,
			'last_name'     => $u->last_name,
			'email'         => $u->user_email,
			'username'      => $u->user_login,
			'avatar'        => OSA_Util::avatar( $user_id ),
			'is_instructor' => OSA_Util::user_can_instructor( $user_id ),
			'instructor_status' => (string) get_user_meta( $user_id, '_tutor_instructor_status', true ),
			'phone'         => (string) get_user_meta( $user_id, 'phone_number', true ),
			'job_title'     => (string) get_user_meta( $user_id, '_tutor_profile_job_title', true ),
			'bio'           => (string) get_user_meta( $user_id, '_tutor_profile_bio', true ),
		);
	}

	public function me() {
		return self::user_payload( get_current_user_id() );
	}

	public function update_me( WP_REST_Request $r ) {
		$id   = get_current_user_id();
		$args = array( 'ID' => $id );
		foreach ( array( 'first_name', 'last_name' ) as $f ) {
			if ( null !== $r->get_param( $f ) ) {
				$args[ $f ] = sanitize_text_field( (string) $r->get_param( $f ) );
			}
		}
		if ( isset( $args['first_name'] ) || isset( $args['last_name'] ) ) {
			$u                 = get_userdata( $id );
			$args['display_name'] = trim( ( $args['first_name'] ?? $u->first_name ) . ' ' . ( $args['last_name'] ?? $u->last_name ) ) ?: $u->display_name;
		}
		$res = wp_update_user( $args );
		if ( is_wp_error( $res ) ) {
			return $res;
		}
		if ( null !== $r->get_param( 'phone' ) ) {
			update_user_meta( $id, 'phone_number', sanitize_text_field( (string) $r->get_param( 'phone' ) ) );
		}
		if ( null !== $r->get_param( 'job_title' ) ) {
			update_user_meta( $id, '_tutor_profile_job_title', sanitize_text_field( (string) $r->get_param( 'job_title' ) ) );
		}
		if ( null !== $r->get_param( 'bio' ) ) {
			update_user_meta( $id, '_tutor_profile_bio', wp_kses_post( (string) $r->get_param( 'bio' ) ) );
		}
		return self::user_payload( $id );
	}

	public function upload_avatar( WP_REST_Request $r ) {
		$files = $r->get_file_params();
		if ( empty( $files['photo'] ) ) {
			return OSA_Util::error( 'no_file', 'Send the image as multipart field "photo".', 400 );
		}
		require_once ABSPATH . 'wp-admin/includes/file.php';
		require_once ABSPATH . 'wp-admin/includes/image.php';
		require_once ABSPATH . 'wp-admin/includes/media.php';
		$_FILES['photo'] = $files['photo'];
		$att = media_handle_upload( 'photo', 0, array(), array( 'test_form' => false, 'mimes' => array( 'jpg|jpeg|jpe' => 'image/jpeg', 'png' => 'image/png', 'webp' => 'image/webp' ) ) );
		if ( is_wp_error( $att ) ) {
			return $att;
		}
		update_user_meta( get_current_user_id(), '_tutor_profile_photo', $att );
		return array( 'avatar' => OSA_Util::avatar( get_current_user_id() ) );
	}

	/* ------------------------------------------------------------------ web session (for pages not built natively) */

	/** POST /auth/web-session {next:"/some/path"} → one-time URL that logs the in-app browser in. */
	public function web_session( WP_REST_Request $r ) {
		$next = (string) $r->get_param( 'next' );
		$next = $next ? $next : '/';
		if ( 0 === strpos( $next, 'http' ) ) {
			$next = wp_validate_redirect( $next, home_url( '/' ) );
		} else {
			$next = home_url( '/' . ltrim( $next, '/' ) );
		}
		$token = wp_generate_password( 40, false, false );
		set_transient( 'osa_sso_' . hash( 'sha256', $token ), array( 'user' => get_current_user_id(), 'next' => $next ), 90 );
		return array( 'url' => add_query_arg( 'ostudy_sso', $token, home_url( '/' ) ) );
	}

	public static function maybe_consume_web_session() {
		if ( empty( $_GET['ostudy_sso'] ) || ! is_string( $_GET['ostudy_sso'] ) ) { // phpcs:ignore WordPress.Security.NonceVerification
			return;
		}
		$key  = 'osa_sso_' . hash( 'sha256', sanitize_text_field( wp_unslash( $_GET['ostudy_sso'] ) ) ); // phpcs:ignore WordPress.Security.NonceVerification
		$data = get_transient( $key );
		delete_transient( $key ); // single use
		if ( ! is_array( $data ) || empty( $data['user'] ) ) {
			wp_safe_redirect( home_url( '/' ) );
			exit;
		}
		wp_set_current_user( (int) $data['user'] );
		wp_set_auth_cookie( (int) $data['user'], true );
		wp_safe_redirect( wp_validate_redirect( $data['next'], home_url( '/' ) ) );
		exit;
	}
}
