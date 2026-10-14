<?php
if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

/** Helpers shared by all endpoints: response shapes, presenters, and access to the site's other plugins. */
final class OSA_Util {

	/** Standard list response. */
	public static function page( array $items, $total, $page, $per_page ) {
		return array(
			'items'    => array_values( $items ),
			'total'    => (int) $total,
			'page'     => (int) $page,
			'pages'    => $per_page > 0 ? (int) ceil( $total / $per_page ) : 1,
			'per_page' => (int) $per_page,
		);
	}

	public static function error( $code, $message, $status = 400, $data = array() ) {
		return new WP_Error( $code, $message, array_merge( array( 'status' => $status ), $data ) );
	}

	public static function user_can_instructor( $user_id = 0 ) {
		$user_id = $user_id ? $user_id : get_current_user_id();
		if ( ! $user_id ) {
			return false;
		}
		if ( user_can( $user_id, 'manage_options' ) ) {
			return true;
		}
		return function_exists( 'tutor_utils' ) ? (bool) tutor_utils()->is_instructor( $user_id, true ) : user_can( $user_id, 'tutor_instructor' );
	}

	/**
	 * Run $fn while selected request params are visible in $_GET, so Tutor Course Search Pro (which reads
	 * $_GET) shapes the query exactly as it does on the website.
	 */
	public static function with_get( WP_REST_Request $request, array $prefixes, callable $fn ) {
		$old   = $_GET; // phpcs:ignore WordPress.Security.NonceVerification
		$extra = array();
		foreach ( (array) $request->get_query_params() as $key => $value ) {
			foreach ( $prefixes as $prefix ) {
				if ( $key === $prefix || 0 === strpos( $key, $prefix ) ) {
					$extra[ $key ] = $value;
				}
			}
		}
		$_GET = array_merge( $_GET, wp_slash( $extra ) ); // phpcs:ignore WordPress.Security.NonceVerification
		try {
			return $fn();
		} finally {
			$_GET = $old; // phpcs:ignore WordPress.Security.NonceVerification
		}
	}

	/** @return array{0:object,1:ReflectionClass}|null The search plugin's tutor-directory object (private API). */
	public static function tcsp_tutors() {
		static $cache = false;
		if ( false !== $cache ) {
			return $cache;
		}
		$cache = null;
		try {
			if ( class_exists( 'TCSP_Plugin' ) && class_exists( 'TCSP_Tutors' ) ) {
				$plugin = TCSP_Plugin::instance();
				$ref    = new ReflectionObject( $plugin );
				if ( $ref->hasProperty( 'tutors' ) ) {
					$prop = $ref->getProperty( 'tutors' );
					$prop->setAccessible( true );
					$obj = $prop->getValue( $plugin );
					if ( $obj ) {
						$cache = array( $obj, new ReflectionClass( $obj ) );
					}
				}
			}
		} catch ( \Throwable $e ) {
			$cache = null;
		}
		return $cache;
	}

	/** Call a (private) method of the search plugin's tutor directory. Returns null if unavailable. */
	public static function tcsp_tutor_call( $method, ...$args ) {
		$t = self::tcsp_tutors();
		if ( ! $t || ! $t[1]->hasMethod( $method ) ) {
			return null;
		}
		try {
			$m = $t[1]->getMethod( $method );
			$m->setAccessible( true );
			return $m->invokeArgs( $t[0], $args );
		} catch ( \Throwable $e ) {
			return null;
		}
	}

	public static function avatar( $user_id, $size = 160 ) {
		$photo = (int) get_user_meta( $user_id, '_tutor_profile_photo', true );
		if ( $photo ) {
			$url = wp_get_attachment_image_url( $photo, 'medium' );
			if ( $url ) {
				return $url;
			}
		}
		return get_avatar_url( $user_id, array( 'size' => $size ) );
	}

	public static function money( $amount ) {
		if ( function_exists( 'tioc_format_price' ) ) {
			return wp_strip_all_tags( tioc_format_price( $amount ) );
		}
		if ( function_exists( 'tutor_utils' ) && method_exists( tutor_utils(), 'tutor_price' ) ) {
			return wp_strip_all_tags( tutor_utils()->tutor_price( $amount ) );
		}
		return number_format_i18n( (float) $amount, 0 ) . ' DZD';
	}

	/** Compact course card used in every list. */
	public static function course_card( $course_id ) {
		$course_id = (int) $course_id;
		$post      = get_post( $course_id );
		if ( ! $post ) {
			return null;
		}
		$type  = (string) get_post_meta( $course_id, '_tutor_course_price_type', true );
		$price = (float) get_post_meta( $course_id, 'tutor_course_price', true );
		$sale  = (float) get_post_meta( $course_id, 'tutor_course_sale_price', true );
		$free  = ( 'free' === $type ) || ( 0.0 >= $price && 0.0 >= $sale );
		$final = $sale > 0 ? $sale : $price;

		$rating = array( 'avg' => 0, 'count' => 0 );
		if ( function_exists( 'tutor_utils' ) ) {
			$r = tutor_utils()->get_course_rating( $course_id );
			if ( $r ) {
				$rating = array( 'avg' => round( (float) $r->rating_avg, 1 ), 'count' => (int) $r->rating_count );
			}
		}
		$enrolled = function_exists( 'tutor_utils' ) ? (int) tutor_utils()->count_enrolled_users_by_course( $course_id ) : 0;

		$cats = wp_get_post_terms( $course_id, 'course-category', array( 'fields' => 'names' ) );

		$format = array();
		if ( function_exists( 'tcf_get_course_format_data' ) ) {
			$format = (array) tcf_get_course_format_data( $course_id );
		}

		$tier    = (string) get_post_meta( $course_id, '_tcsp_promotion_tier', true );
		$author  = (int) $post->post_author;
		$author_u = get_userdata( $author );

		return array(
			'id'          => $course_id,
			'title'       => wp_strip_all_tags( get_the_title( $course_id ) ),
			'url'         => get_permalink( $course_id ),
			'excerpt'     => wp_trim_words( wp_strip_all_tags( $post->post_excerpt ? $post->post_excerpt : $post->post_content ), 28 ),
			'thumbnail'   => get_the_post_thumbnail_url( $course_id, 'large' ) ?: null,
			'is_free'     => $free,
			'price'       => $free ? 0 : $price,
			'sale_price'  => $sale > 0 ? $sale : null,
			'price_text'  => $free ? '' : self::money( $final ),
			'rating'      => $rating,
			'students'    => $enrolled,
			'level'       => (string) get_post_meta( $course_id, '_tutor_course_level', true ),
			'categories'  => is_wp_error( $cats ) ? array() : array_values( $cats ),
			'format'      => $format,
			'sponsored'   => '' !== $tier,
			'instructor'  => array(
				'id'     => $author,
				'name'   => $author_u ? $author_u->display_name : '',
				'avatar' => self::avatar( $author, 96 ),
			),
		);
	}

	/** Tutor card/profile from the search plugin's helpers (falls back to plain user meta). */
	public static function tutor_card( $user_id, $full = false ) {
		$user_id = (int) $user_id;
		$u       = get_userdata( $user_id );
		if ( ! $u ) {
			return null;
		}
		$stats = array( 'rating' => 0, 'reviews' => 0, 'courses' => 0, 'students' => 0 );
		$s     = self::tcsp_tutor_call( 'stats', array( $user_id ) );
		if ( is_array( $s ) && isset( $s[ $user_id ] ) ) {
			$stats = $s[ $user_id ];
		}
		$job = self::tcsp_tutor_call( 'tutor_job_title', $user_id );
		$bio = self::tcsp_tutor_call( 'tutor_bio', $user_id );
		if ( null === $job ) {
			$job = (string) get_user_meta( $user_id, '_tutor_profile_job_title', true );
		}
		if ( null === $bio ) {
			$bio = (string) get_user_meta( $user_id, '_tutor_profile_bio', true );
		}
		$tier = (string) get_user_meta( $user_id, '_tcsp_tutor_promotion_tier', true );

		$card = array(
			'id'          => $user_id,
			'name'        => $u->display_name,
			'avatar'      => self::avatar( $user_id ),
			'job_title'   => wp_strip_all_tags( (string) $job ),
			'bio'         => $full ? wp_kses_post( (string) $bio ) : wp_trim_words( wp_strip_all_tags( (string) $bio ), 30 ),
			'stats'       => $stats,
			'location'    => (array) self::tcsp_tutor_call( 'location_labels', $user_id ),
			'languages'   => (array) self::tcsp_tutor_call( 'language_labels', $user_id ),
			'types'       => (array) self::tcsp_tutor_call( 'type_labels', $user_id ),
			'categories'  => (array) self::tcsp_tutor_call( 'category_names', $user_id ),
			'experience'  => (int) self::tcsp_tutor_call( 'experience_years', $user_id ),
			'verified'    => 'verified' === strtolower( trim( (string) get_user_meta( $user_id, '_tlms_ipe_verification_status', true ) ) ),
			'private_sessions' => '1' === (string) get_user_meta( $user_id, '_tlms_ipe_accept_private_sessions', true ),
			'sponsored'   => '' !== $tier,
			'url'         => function_exists( 'tutor_utils' ) ? tutor_utils()->profile_url( $user_id, true ) : get_author_posts_url( $user_id ),
		);
		if ( $full ) {
			$card['education'] = (array) self::tcsp_tutor_call( 'education_rows', $user_id );
		}
		return $card;
	}
}
