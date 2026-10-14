<?php
/**
 * Plugin Name: Ostudy Mobile API
 * Description: REST API for the native Ostudy Android app: app-password login, courses & tutors search (reuses Tutor Course Search Pro), course learning, offline-payment checkout and instructor payment tools.
 * Version: 0.1.0
 * Requires PHP: 7.4
 * Requires at least: 6.0
 * Author: Ostudy Academy
 * License: GPL-2.0+
 * Text Domain: ostudy-mobile-api
 */

if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

define( 'OSA_VERSION', '0.1.0' );
define( 'OSA_NS', 'ostudy/v1' );
define( 'OSA_DIR', plugin_dir_path( __FILE__ ) );

require_once OSA_DIR . 'includes/class-osa-util.php';
require_once OSA_DIR . 'includes/class-osa-auth.php';
require_once OSA_DIR . 'includes/class-osa-catalog.php';
require_once OSA_DIR . 'includes/class-osa-commerce.php';

// The one-time web-session link is a normal front-end request, so hook it outside rest_api_init.
add_action( 'template_redirect', array( 'OSA_Auth', 'maybe_consume_web_session' ), 1 );

add_action(
	'rest_api_init',
	function () {
		( new OSA_Auth() )->routes();
		( new OSA_Catalog() )->routes();
		( new OSA_Commerce() )->routes();

		register_rest_route(
			OSA_NS,
			'/config',
			array(
				'methods'             => 'GET',
				'permission_callback' => '__return_true',
				'callback'            => function () {
					$locale = determine_locale();
					return array(
						'api_version'        => OSA_VERSION,
						'site_name'          => get_bloginfo( 'name' ),
						'site_url'           => home_url( '/' ),
						'locale'             => $locale,
						'currency'           => 'DZD',
						'registration_open'  => (bool) get_option( 'users_can_register' ),
						'app_passwords'      => function_exists( 'wp_is_application_passwords_available' ) && wp_is_application_passwords_available(),
						'features'           => array(
							'tutor'           => function_exists( 'tutor_utils' ),
							'course_search'   => class_exists( 'TCSP_Plugin' ),
							'offline_payment' => class_exists( '\TutorInstructorOfflinePayment\Checkout' ),
							'private_sessions' => class_exists( 'TLMS_Private_Sessions' ) || defined( 'TLMS_PS_VERSION' ),
							'better_messages' => function_exists( 'Better_Messages' ),
						),
					);
				},
			)
		);

		// Admin-only: which integrations the API found on THIS site. Use it to debug a first install.
		register_rest_route(
			OSA_NS,
			'/diagnostics',
			array(
				'methods'             => 'GET',
				'permission_callback' => function () {
					return current_user_can( 'manage_options' );
				},
				'callback'            => function () {
					$check = array(
						'php'                       => PHP_VERSION,
						'wp'                        => get_bloginfo( 'version' ),
						'tutor_function'            => function_exists( 'tutor_utils' ),
						'tutor_version'             => defined( 'TUTOR_VERSION' ) ? TUTOR_VERSION : null,
						'tutor_buy_now'             => class_exists( '\Tutor\Ecommerce\Settings' ) ? (bool) \Tutor\Ecommerce\Settings::is_buy_now_enabled() : null,
						'tutor_cart_model'          => class_exists( '\Tutor\Models\CartModel' ),
						'tcsp_plugin'               => class_exists( 'TCSP_Plugin' ),
						'tcsp_tutors_reflection'    => OSA_Util::tcsp_tutors() ? true : false,
						'tioc_checkout'             => class_exists( '\TutorInstructorOfflinePayment\Checkout' ),
						'tioc_orders'               => class_exists( '\TutorInstructorOfflinePayment\Orders' ),
						'tcf_helpers'               => function_exists( 'tcf_get_course_format_data' ),
						'tlms_ipe'                  => class_exists( 'Tlms_Ipe_Plugin' ),
						'better_messages'           => function_exists( 'Better_Messages' ),
						'app_passwords_available'   => function_exists( 'wp_is_application_passwords_available' ) && wp_is_application_passwords_available(),
						'https'                     => is_ssl(),
						'authorization_header_seen' => isset( $_SERVER['HTTP_AUTHORIZATION'] ) || isset( $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ),
					);
					return $check;
				},
			)
		);
	}
);
