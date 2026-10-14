<?php
if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

use TutorInstructorOfflinePayment\Checkout;
use TutorInstructorOfflinePayment\Methods;
use TutorInstructorOfflinePayment\Orders;
use TutorInstructorOfflinePayment\Uploads;

/** Thrown from the wp_redirect filter so the website's checkout handler can be run without it ending the request. */
class OSA_Redirect_Signal extends Exception {
	public $location = '';
}

/**
 * Offline-payment checkout + student orders + instructor payment tools.
 * Everything delegates to the "Tutor Instructor Offline Payment" plugin so the app follows exactly the same rules as the website.
 */
class OSA_Commerce {

	public function routes() {
		$auth = function () {
			return is_user_logged_in() ? true : OSA_Util::error( 'not_logged_in', 'Authentication required.', 401 );
		};
		$inst = function () {
			if ( ! is_user_logged_in() ) {
				return OSA_Util::error( 'not_logged_in', 'Authentication required.', 401 );
			}
			return OSA_Util::user_can_instructor() ? true : OSA_Util::error( 'forbidden', 'Instructor account required.', 403 );
		};

		register_rest_route( OSA_NS, '/checkout/preview', array( 'methods' => 'GET', 'permission_callback' => $auth, 'callback' => array( $this, 'preview' ) ) );
		register_rest_route( OSA_NS, '/checkout', array( 'methods' => 'POST', 'permission_callback' => $auth, 'callback' => array( $this, 'place' ) ) );
		register_rest_route( OSA_NS, '/orders', array( 'methods' => 'GET', 'permission_callback' => $auth, 'callback' => array( $this, 'my_orders' ) ) );
		register_rest_route( OSA_NS, '/orders/(?P<id>\d+)', array( 'methods' => 'GET', 'permission_callback' => $auth, 'callback' => array( $this, 'order' ) ) );
		register_rest_route( OSA_NS, '/orders/(?P<id>\d+)/proof', array( 'methods' => 'POST', 'permission_callback' => $auth, 'callback' => array( $this, 'submit_proof' ) ) );
		register_rest_route( OSA_NS, '/orders/(?P<id>\d+)/receipt', array( 'methods' => 'GET', 'permission_callback' => $auth, 'callback' => array( $this, 'receipt' ) ) );

		register_rest_route( OSA_NS, '/instructor/overview', array( 'methods' => 'GET', 'permission_callback' => $inst, 'callback' => array( $this, 'overview' ) ) );
		register_rest_route( OSA_NS, '/instructor/payments', array( 'methods' => 'GET', 'permission_callback' => $inst, 'callback' => array( $this, 'instructor_orders' ) ) );
		register_rest_route( OSA_NS, '/instructor/payments/(?P<id>\d+)/approve', array( 'methods' => 'POST', 'permission_callback' => $inst, 'callback' => array( $this, 'approve' ) ) );
		register_rest_route( OSA_NS, '/instructor/payments/(?P<id>\d+)/reject', array( 'methods' => 'POST', 'permission_callback' => $inst, 'callback' => array( $this, 'reject' ) ) );
		register_rest_route(
			OSA_NS,
			'/instructor/payment-methods',
			array(
				array( 'methods' => 'GET', 'permission_callback' => $inst, 'callback' => array( $this, 'methods_list' ) ),
				array( 'methods' => 'POST', 'permission_callback' => $inst, 'callback' => array( $this, 'method_save' ) ),
			)
		);
		register_rest_route( OSA_NS, '/instructor/payment-methods/(?P<id>[A-Za-z0-9_-]+)', array( 'methods' => 'DELETE', 'permission_callback' => $inst, 'callback' => array( $this, 'method_delete' ) ) );
		register_rest_route( OSA_NS, '/instructor/payment-methods/types', array( 'methods' => 'GET', 'permission_callback' => $inst, 'callback' => array( $this, 'method_types' ) ) );
	}

	private function need_plugin() {
		if ( ! class_exists( '\TutorInstructorOfflinePayment\Checkout' ) ) {
			return OSA_Util::error( 'plugin_missing', 'The offline payment plugin is not active.', 501 );
		}
		return null;
	}

	/* ------------------------------------------------------------------ order presenters */

	private function order_payload( $row, $detail = false ) {
		$id    = (int) $row->id;
		$items = array();
		foreach ( (array) Orders::get_items( $id ) as $it ) {
			$items[] = array( 'id' => (int) $it->id, 'title' => wp_strip_all_tags( (string) $it->title ), 'thumbnail' => get_the_post_thumbnail_url( (int) $it->id, 'medium' ) ?: null );
		}
		$proof    = Orders::get_proof( $id );
		$payee_id = (int) ( $row->instructor_id ?? Orders::get_instructor_id( $id ) );
		$uid      = get_current_user_id();
		$out      = array(
			'id'             => $id,
			'status'         => (string) $row->payment_status,
			'status_label'   => wp_strip_all_tags( (string) Orders::status_label( $row->payment_status ) ),
			'total'          => (float) ( $row->total_price ?? 0 ),
			'total_text'     => OSA_Util::money( (float) ( $row->total_price ?? 0 ) ),
			'created'        => (string) ( $row->created_at_gmt ?? '' ),
			'items'          => $items,
			'student'        => array( 'id' => (int) $row->user_id, 'name' => (string) ( $row->student_name ?? '' ) ),
			'instructor'     => array( 'id' => $payee_id, 'name' => $payee_id ? ( get_userdata( $payee_id )->display_name ?? '' ) : '' ),
			'method'         => (string) Orders::get_meta( $id, Orders::META_METHOD_TITLE ),
			'reference'      => (string) Orders::get_meta( $id, Orders::META_REFERENCE ),
			'has_receipt'    => ! empty( $proof['file'] ),
			'reject_reason'  => (string) Orders::get_meta( $id, Orders::META_REJECT_REASON ),
			'deadline'       => (string) Orders::get_meta( $id, Orders::META_DEADLINE_AT ),
			'can_manage'     => (bool) Orders::can_manage( $id, $uid ),
			'can_submit_proof' => (bool) Orders::can_submit_proof( $id, $uid ),
		);
		if ( $detail ) {
			$out['note']    = (string) Orders::get_meta( $id, Orders::META_STUDENT_NOTE );
			$out['history'] = array_values( (array) Orders::get_history( $id ) );
			$snap           = Orders::get_meta( $id, Orders::META_METHOD_SNAPSHOT, array() );
			$out['method_details'] = is_array( $snap ) ? $snap : array();
			$out['receipt_url']    = ! empty( $proof['file'] ) ? rest_url( OSA_NS . '/orders/' . $id . '/receipt' ) : null;
		}
		return $out;
	}

	public function my_orders( WP_REST_Request $r ) {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$page = max( 1, (int) $r->get_param( 'page' ) );
		$per  = 20;
		$args = array( 'status' => (string) ( $r->get_param( 'status' ) ?: 'any' ), 'limit' => $per, 'offset' => ( $page - 1 ) * $per );
		$uid  = get_current_user_id();
		$rows = Orders::get_for_student( $uid, $args );
		return OSA_Util::page( array_map( array( $this, 'order_payload' ), $rows ), Orders::count_for_student( $uid, $args ), $page, $per );
	}

	public function order( WP_REST_Request $r ) {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$id = (int) $r['id'];
		if ( ! Orders::can_view( $id, get_current_user_id() ) ) {
			return OSA_Util::error( 'forbidden', 'You cannot view this order.', 403 );
		}
		$row = Orders::get_row( $id );
		if ( ! $row ) {
			return OSA_Util::error( 'not_found', 'Order not found.', 404 );
		}
		return $this->order_payload( $row, true );
	}

	/** Streams the receipt image/PDF through the website's own permission check. */
	public function receipt( WP_REST_Request $r ) {
		$id = (int) $r['id'];
		if ( ! Orders::can_view( $id, get_current_user_id() ) ) {
			return OSA_Util::error( 'forbidden', 'You cannot view this receipt.', 403 );
		}
		$proof = Orders::get_proof( $id );
		$path  = ! empty( $proof['file'] ) ? Uploads::resolve( $proof['file'] ) : '';
		if ( ! $path || ! is_readable( $path ) ) {
			return OSA_Util::error( 'not_found', 'No receipt.', 404 );
		}
		$mime = function_exists( 'mime_content_type' ) ? mime_content_type( $path ) : 'application/octet-stream';
		while ( ob_get_level() ) {
			ob_end_clean();
		}
		nocache_headers();
		header( 'Content-Type: ' . $mime );
		header( 'Content-Length: ' . filesize( $path ) );
		header( 'X-Content-Type-Options: nosniff' );
		readfile( $path ); // phpcs:ignore WordPress.WP.AlternativeFunctions
		exit;
	}

	public function submit_proof( WP_REST_Request $r ) {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$id  = (int) $r['id'];
		$uid = get_current_user_id();
		if ( ! Orders::can_submit_proof( $id, $uid ) ) {
			return OSA_Util::error( 'closed', 'This order can no longer accept a receipt.', 403 );
		}
		$reference = sanitize_text_field( (string) $r->get_param( 'reference' ) );
		$files     = $r->get_file_params();
		$file      = $files['proof'] ?? null;
		$has_file  = is_array( $file ) && ! empty( $file['tmp_name'] );
		$old       = Orders::get_proof( $id );
		$row       = Orders::get_row( $id );
		$resubmit  = $row && \Tutor\Models\OrderModel::PAYMENT_FAILED === $row->payment_status;

		if ( ! $reference && ! $has_file && empty( $old['file'] ) ) {
			return OSA_Util::error( 'empty', 'Add a transaction reference or attach a receipt.', 400 );
		}
		if ( $resubmit && ! $has_file ) {
			return OSA_Util::error( 'need_new_receipt', 'Attach a new receipt before resubmitting a rejected payment.', 400 );
		}
		if ( $reference ) {
			Orders::set_meta( $id, Orders::META_REFERENCE, $reference );
		}
		if ( $has_file ) {
			$stored = Uploads::store( $file );
			if ( is_wp_error( $stored ) ) {
				return OSA_Util::error( 'upload_failed', $stored->get_error_message(), 400 );
			}
			Orders::set_meta( $id, Orders::META_PROOF, $stored );
			if ( ! empty( $old['file'] ) ) {
				Uploads::delete( $old['file'] );
			}
		}
		$reopened = Orders::reopen( $id );
		Orders::add_activity( $id, $reopened ? 'The student resubmitted payment details after a rejection.' : 'The student sent additional payment details after checkout.' );
		do_action( 'tioc_proof_submitted_later', $id, $uid );
		return array( 'success' => true );
	}

	/* ------------------------------------------------------------------ checkout */

	/** Make the website's checkout see the chosen course ("buy now"), or fall back to the Tutor cart. */
	private function select_course( $course_id ) {
		$_REQUEST['course_id'] = $course_id; // phpcs:ignore WordPress.Security.NonceVerification
		$_GET['course_id']     = $course_id; // phpcs:ignore WordPress.Security.NonceVerification
		if ( class_exists( '\Tutor\Ecommerce\Settings' ) && \Tutor\Ecommerce\Settings::is_buy_now_enabled() ) {
			return true;
		}
		// Buy-now is off: put the course into the Tutor cart (clearing nothing else).
		if ( class_exists( '\Tutor\Models\CartModel' ) ) {
			$cart = new \Tutor\Models\CartModel();
			if ( method_exists( $cart, 'add_course_to_cart' ) ) {
				$res = $cart->add_course_to_cart( get_current_user_id(), $course_id );
				return false !== $res;
			}
		}
		return false;
	}

	public function preview( WP_REST_Request $r ) {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$course_id = (int) $r->get_param( 'course_id' );
		if ( 'courses' !== get_post_type( $course_id ) ) {
			return OSA_Util::error( 'not_found', 'Course not found.', 404 );
		}
		if ( function_exists( 'tutor_utils' ) && tutor_utils()->is_enrolled( $course_id, get_current_user_id() ) ) {
			return OSA_Util::error( 'already_enrolled', 'You are already enrolled in this course.', 409 );
		}
		if ( ! $this->select_course( $course_id ) ) {
			return OSA_Util::error( 'cart_unavailable', 'Could not start checkout for this course on this site (see /ostudy/v1/diagnostics).', 501 );
		}
		$_REQUEST['coupon_code'] = sanitize_text_field( (string) $r->get_param( 'coupon' ) );
		$view = Checkout::get_view();

		$groups = array();
		foreach ( $view->groups as $g ) {
			$methods = array();
			foreach ( $g->methods as $m ) {
				$details = array();
				foreach ( (array) Methods::public_details( $m ) as $label => $row ) {
					$details[] = is_array( $row ) ? $row : array( 'label' => (string) $label, 'value' => (string) $row );
				}
				$methods[] = array(
					'id'      => $m['id'],
					'title'   => $m['title'],
					'type'    => $m['profile_type'] ?? '',
					'details' => $details,
					'image'   => Methods::image_url( $m ) ?: null,
					'instructions' => isset( $m['instructions'] ) ? wp_strip_all_tags( (string) $m['instructions'] ) : '',
				);
			}
			$contact = (array) $g->contact;
			$groups[] = array(
				'instructor'  => array( 'id' => $g->instructor_id, 'name' => $g->instructor_name, 'avatar' => $g->instructor_photo ),
				'course_ids'  => $g->item_ids,
				'titles'      => array_map( function ( $c ) { return wp_strip_all_tags( $c->post_title ); }, $g->courses ),
				'total'       => $g->total,
				'total_text'  => OSA_Util::money( $g->total ),
				'is_free'     => $g->is_free,
				'configured'  => $g->configured,
				'note'        => wp_strip_all_tags( (string) $g->note ),
				'methods'     => $methods,
				'contact'     => array(
					'phone'    => $contact['phone'] ?? '',
					'whatsapp' => ! empty( $contact['show_whatsapp'] ) ? ( $contact['whatsapp'] ?? '' ) : '',
					'email'    => ! empty( $contact['show_email'] ) ? ( $contact['email'] ?? '' ) : '',
				),
			);
		}

		return array(
			'groups'          => $groups,
			'subtotal'        => $view->subtotal,
			'discount'        => $view->sale_discount + $view->coupon_discount,
			'total'           => $view->total,
			'total_text'      => OSA_Util::money( $view->total ),
			'blocked'         => (bool) $view->blocked,
			'notices'         => array_map( 'wp_strip_all_tags', (array) $view->notices ),
			'collect_reference' => (bool) \TutorInstructorOfflinePayment\Settings::get( 'collect_reference', 1 ),
			'require_proof'   => (bool) \TutorInstructorOfflinePayment\Settings::get( 'require_proof', 0 ),
		);
	}

	/**
	 * POST /checkout  (multipart or JSON)
	 *   course_id, method_id, reference, note, coupon, proof (file)
	 * One course per call. The website's place_order() does validation, order creation and e-mails.
	 */
	public function place( WP_REST_Request $r ) {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$uid       = get_current_user_id();
		$course_id = (int) $r->get_param( 'course_id' );
		if ( 'courses' !== get_post_type( $course_id ) ) {
			return OSA_Util::error( 'not_found', 'Course not found.', 404 );
		}
		if ( ! $this->select_course( $course_id ) ) {
			return OSA_Util::error( 'cart_unavailable', 'Could not start checkout for this course on this site.', 501 );
		}

		// Payee of this course = group key used by the website form.
		$payee = function_exists( 'tioc_get_payee_id' ) ? (int) tioc_get_payee_id( $course_id ) : (int) get_post_field( 'post_author', $course_id );

		$_REQUEST['coupon_code'] = sanitize_text_field( (string) $r->get_param( 'coupon' ) );
		$_POST = array(
			'tioc_nonce'     => wp_create_nonce( Checkout::ACTION ),
			'tioc_method'    => array( $payee => sanitize_key( (string) $r->get_param( 'method_id' ) ) ),
			'tioc_reference' => array( $payee => (string) $r->get_param( 'reference' ) ),
			'tioc_note'      => array( $payee => (string) $r->get_param( 'note' ) ),
			'course_id'      => $course_id,
		);
		foreach ( (array) $r->get_body_params() as $k => $v ) { // consent checkboxes etc.
			if ( 0 === strpos( (string) $k, 'tutor_' ) || 0 === strpos( (string) $k, 'consent' ) ) {
				$_POST[ $k ] = $v;
			}
		}
		$_POST = wp_slash( $_POST );

		// The website field is an array indexed by payee: tioc_proof[name|type|tmp_name|error|size][payee]
		unset( $_FILES['tioc_proof'] );
		$files = $r->get_file_params();
		if ( ! empty( $files['proof']['tmp_name'] ) && is_string( $files['proof']['tmp_name'] ) ) {
			$f = $files['proof'];
			$_FILES['tioc_proof'] = array(
				'name'     => array( $payee => $f['name'] ),
				'type'     => array( $payee => $f['type'] ),
				'tmp_name' => array( $payee => $f['tmp_name'] ),
				'error'    => array( $payee => $f['error'] ),
				'size'     => array( $payee => $f['size'] ),
			);
		}

		// Run the website's own handler; capture its redirect instead of letting it exit.
		$capture = function ( $location ) {
			$sig           = new OSA_Redirect_Signal( 'redirect' );
			$sig->location = $location;
			throw $sig;
		};
		add_filter( 'wp_redirect', $capture, 0 );
		$location = '';
		try {
			( new Checkout() )->place_order();
		} catch ( OSA_Redirect_Signal $sig ) {
			$location = $sig->location;
		} finally {
			remove_filter( 'wp_redirect', $capture, 0 );
		}

		$errors = Checkout::take_errors( $uid );
		if ( ! empty( $errors ) ) {
			return OSA_Util::error( 'checkout_failed', implode( ' ', array_map( 'wp_strip_all_tags', (array) $errors ) ), 422, array( 'errors' => array_values( (array) $errors ) ) );
		}
		if ( false === strpos( (string) $location, 'tioc_order_placed' ) ) {
			return OSA_Util::error( 'checkout_failed', 'Your order could not be placed. Please try again.', 422 );
		}

		// Newest order of this student is the one just created.
		$rows = Orders::get_for_student( $uid, array( 'limit' => 1 ) );
		return array( 'success' => true, 'order' => $rows ? $this->order_payload( $rows[0], true ) : null );
	}

	/* ------------------------------------------------------------------ instructor */

	public function overview() {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$uid = get_current_user_id();
		return array(
			'pending_payments' => (int) Orders::pending_count( $uid ),
			'stats'            => Orders::instructor_stats( $uid ),
			'has_payment_methods' => (bool) Methods::has_active( $uid ),
		);
	}

	public function instructor_orders( WP_REST_Request $r ) {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$page = max( 1, (int) $r->get_param( 'page' ) );
		$per  = 20;
		$args = array(
			'status' => (string) ( $r->get_param( 'status' ) ?: 'any' ),
			'search' => sanitize_text_field( (string) $r->get_param( 'q' ) ),
			'limit'  => $per,
			'offset' => ( $page - 1 ) * $per,
		);
		$uid  = get_current_user_id();
		$rows = Orders::get_for_instructor( $uid, $args );
		return OSA_Util::page( array_map( function ( $row ) { return $this->order_payload( $row, true ); }, $rows ), Orders::count_for_instructor( $uid, $args ), $page, $per );
	}

	public function approve( WP_REST_Request $r ) {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$res = Orders::approve( (int) $r['id'], sanitize_textarea_field( (string) $r->get_param( 'note' ) ), get_current_user_id() );
		return is_wp_error( $res ) ? OSA_Util::error( $res->get_error_code(), $res->get_error_message(), 'tioc_forbidden' === $res->get_error_code() ? 403 : 400 ) : array( 'success' => true );
	}

	public function reject( WP_REST_Request $r ) {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$reason = sanitize_textarea_field( (string) $r->get_param( 'reason' ) );
		if ( '' === trim( $reason ) ) {
			return OSA_Util::error( 'reason_required', 'Tell the student why the payment was rejected.', 400 );
		}
		$res = Orders::reject( (int) $r['id'], $reason, get_current_user_id() );
		return is_wp_error( $res ) ? OSA_Util::error( $res->get_error_code(), $res->get_error_message(), 'tioc_forbidden' === $res->get_error_code() ? 403 : 400 ) : array( 'success' => true );
	}

	private function method_payload( array $m ) {
		return array(
			'id'        => $m['id'],
			'title'     => $m['title'],
			'type'      => $m['profile_type'] ?? '',
			'profile'   => $m['profile'] ?? new stdClass(),
			'instructions' => $m['instructions'] ?? '',
			'is_active' => ! empty( $m['is_active'] ),
			'image'     => Methods::image_url( $m ) ?: null,
		);
	}

	public function method_types() {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$out = array();
		foreach ( (array) Methods::types() as $key => $def ) {
			$out[] = array( 'type' => $key, 'definition' => $def );
		}
		return $out;
	}

	public function methods_list() {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$uid = get_current_user_id();
		return array(
			'methods' => array_map( array( $this, 'method_payload' ), Methods::get( $uid ) ),
			'note'    => Methods::get_note( $uid ),
			'max'     => Methods::MAX_METHODS,
		);
	}

	/** Same input array the website dashboard form posts (id, title, profile_type, profile[…], instructions, is_active). */
	public function method_save( WP_REST_Request $r ) {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$uid   = get_current_user_id();
		$input = (array) $r->get_json_params();
		if ( ! $input ) {
			$input = (array) $r->get_body_params();
		}
		if ( isset( $input['note'] ) && ! isset( $input['title'] ) ) {
			Methods::save_note( $uid, (string) $input['note'] );
			return array( 'success' => true );
		}
		$res = Methods::save( $uid, wp_unslash( wp_slash( $input ) ) );
		if ( is_wp_error( $res ) ) {
			return OSA_Util::error( $res->get_error_code(), $res->get_error_message(), 400 );
		}
		if ( isset( $input['note'] ) ) {
			Methods::save_note( $uid, (string) $input['note'] );
		}
		return array( 'success' => true, 'method' => is_array( $res ) ? $res : null );
	}

	public function method_delete( WP_REST_Request $r ) {
		if ( $e = $this->need_plugin() ) {
			return $e;
		}
		$res = Methods::delete( get_current_user_id(), sanitize_key( (string) $r['id'] ) );
		return is_wp_error( $res ) ? OSA_Util::error( $res->get_error_code(), $res->get_error_message(), 400 ) : array( 'success' => true );
	}
}
