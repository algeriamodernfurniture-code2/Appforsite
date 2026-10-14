<?php
if ( ! defined( 'ABSPATH' ) ) {
	exit;
}

/** Courses, tutors, filters, learning content. Search/filter logic is the website's own (Tutor Course Search Pro). */
class OSA_Catalog {

	public function routes() {
		$open = '__return_true';
		$auth = function () {
			return is_user_logged_in() ? true : OSA_Util::error( 'not_logged_in', 'Authentication required.', 401 );
		};

		register_rest_route( OSA_NS, '/filters', array( 'methods' => 'GET', 'permission_callback' => $open, 'callback' => array( $this, 'filters' ) ) );
		register_rest_route( OSA_NS, '/courses', array( 'methods' => 'GET', 'permission_callback' => $open, 'callback' => array( $this, 'courses' ) ) );
		register_rest_route( OSA_NS, '/courses/(?P<id>\d+)', array( 'methods' => 'GET', 'permission_callback' => $open, 'callback' => array( $this, 'course' ) ) );
		register_rest_route( OSA_NS, '/courses/(?P<id>\d+)/enroll', array( 'methods' => 'POST', 'permission_callback' => $auth, 'callback' => array( $this, 'enroll_free' ) ) );
		register_rest_route( OSA_NS, '/tutors', array( 'methods' => 'GET', 'permission_callback' => $open, 'callback' => array( $this, 'tutors' ) ) );
		register_rest_route( OSA_NS, '/tutors/(?P<id>\d+)', array( 'methods' => 'GET', 'permission_callback' => $open, 'callback' => array( $this, 'tutor' ) ) );
		register_rest_route( OSA_NS, '/me/courses', array( 'methods' => 'GET', 'permission_callback' => $auth, 'callback' => array( $this, 'my_courses' ) ) );
		register_rest_route( OSA_NS, '/lessons/(?P<id>\d+)', array( 'methods' => 'GET', 'permission_callback' => $auth, 'callback' => array( $this, 'lesson' ) ) );
		register_rest_route( OSA_NS, '/lessons/(?P<id>\d+)/complete', array( 'methods' => 'POST', 'permission_callback' => $auth, 'callback' => array( $this, 'complete_lesson' ) ) );
	}

	/* ------------------------------------------------------------------ filters */

	private function tcsp_prop( $prop ) {
		try {
			if ( ! class_exists( 'TCSP_Plugin' ) ) {
				return null;
			}
			$plugin = TCSP_Plugin::instance();
			$ref    = new ReflectionObject( $plugin );
			if ( ! $ref->hasProperty( $prop ) ) {
				return null;
			}
			$p = $ref->getProperty( $prop );
			$p->setAccessible( true );
			return $p->getValue( $plugin );
		} catch ( \Throwable $e ) {
			return null;
		}
	}

	private function enabled( $key ) {
		$settings = $this->tcsp_prop( 'settings' );
		return $settings ? (bool) $settings->get( $key ) : false;
	}

	/** Normalise a plugin choice list (key => label, or key => array) to [{value,label,...}]. */
	private function choices( $raw ) {
		$out = array();
		foreach ( (array) $raw as $key => $val ) {
			if ( is_array( $val ) ) {
				$value = is_string( $key ) ? $key : (string) ( $val['value'] ?? '' );
				$label = (string) ( $val['label'] ?? $val['name'] ?? $value );
				$row   = array( 'value' => $value, 'label' => $label );
				if ( isset( $val['wilaya'] ) ) {
					$row['wilaya'] = (string) $val['wilaya'];
				}
			} else {
				$value = is_string( $key ) ? $key : (string) $val;
				$row   = array( 'value' => $value, 'label' => (string) $val );
			}
			if ( '' !== $row['value'] ) {
				$out[] = $row;
			}
		}
		return $out;
	}

	private function ui_choices( $method ) {
		$ui = $this->tcsp_prop( 'filters_ui' );
		if ( ! $ui ) {
			return array();
		}
		try {
			$ref = new ReflectionMethod( $ui, $method );
			$ref->setAccessible( true );
			return $this->choices( $ref->invoke( $ui ) );
		} catch ( \Throwable $e ) {
			return array();
		}
	}

	private function term_choices( $taxonomy ) {
		$terms = get_terms( array( 'taxonomy' => $taxonomy, 'hide_empty' => false ) );
		$out   = array();
		if ( ! is_wp_error( $terms ) ) {
			foreach ( $terms as $t ) {
				$out[] = array( 'value' => $t->slug, 'label' => $t->name, 'parent' => (int) $t->parent );
			}
		}
		return $out;
	}

	public function filters() {
		$t = 'tutor-course-search-pro';
		$course_filters = array();
		$add = function ( &$list, $setting, $key, $param, $type, $label, $options ) {
			if ( $this->enabled( $setting ) ) {
				$list[] = array( 'key' => $key, 'param' => $param, 'type' => $type, 'label' => $label, 'options' => $options );
			}
		};

		$add( $course_filters, 'enable_course_type_filter', 'course_type', 'tcsp_course_type[]', 'multi', __( 'Course type', $t ), $this->ui_choices( 'course_type_choices' ) );
		$add( $course_filters, 'enable_tutoring_format_filter', 'tutoring_format', 'tcsp_tutoring_format[]', 'multi', __( 'Tutoring format', $t ), $this->ui_choices( 'tutoring_format_choices' ) );
		$add( $course_filters, 'enable_category_filter', 'category', 'tcsp_category[]', 'multi', __( 'Category', $t ), $this->term_choices( 'course-category' ) );
		$add( $course_filters, 'enable_tag_filter', 'tag', 'tcsp_tag[]', 'multi', __( 'Tag', $t ), $this->term_choices( 'course-tag' ) );
		$add( $course_filters, 'enable_spoken_language_filter', 'language', 'tcsp_language[]', 'multi', __( 'Languages', $t ), $this->ui_choices( 'profile_language_choices' ) );
		$add( $course_filters, 'enable_tutoring_type_filter', 'tutoring_type', 'tcsp_tutoring_type[]', 'multi', __( 'Tutoring type', $t ), $this->ui_choices( 'profile_tutoring_choices' ) );
		$add( $course_filters, 'enable_wilaya_filter', 'wilaya', 'tcsp_wilaya[]', 'multi', __( 'Wilaya', $t ), $this->ui_choices( 'wilaya_filter_choices' ) );
		$add( $course_filters, 'enable_municipality_filter', 'municipality', 'tcsp_municipality[]', 'multi', __( 'Municipality', $t ), $this->ui_choices( 'municipality_filter_choices' ) );
		$add( $course_filters, 'enable_price_filter', 'price', 'tcsp_price', 'single', __( 'Price', $t ), array( array( 'value' => 'free', 'label' => __( 'Free', $t ) ), array( 'value' => 'paid', 'label' => __( 'Paid', $t ) ) ) );
		$add(
			$course_filters,
			'enable_level_filter',
			'level',
			'tcsp_level',
			'single',
			__( 'Level', $t ),
			array(
				array( 'value' => 'beginner', 'label' => __( 'Beginner', 'tutor' ) ),
				array( 'value' => 'intermediate', 'label' => __( 'Intermediate', 'tutor' ) ),
				array( 'value' => 'expert', 'label' => __( 'Expert', 'tutor' ) ),
				array( 'value' => 'all_levels', 'label' => __( 'All levels', 'tutor' ) ),
			)
		);
		$add( $course_filters, 'enable_rating_filter', 'rating', 'tcsp_min_rating', 'single', __( 'Minimum rating', $t ), array_map( function ( $n ) { return array( 'value' => (string) $n, 'label' => $n . '★+' ); }, array( 4, 3, 2, 1 ) ) );
		$add( $course_filters, 'enable_verified_filter', 'verified', 'tcsp_verified', 'single', __( 'Verified instructor', $t ), array( array( 'value' => 'verified', 'label' => __( 'Verified only', $t ) ) ) );

		$sorts = array();
		$settings = $this->tcsp_prop( 'settings' );
		if ( $settings && method_exists( $settings, 'sort_options' ) ) {
			foreach ( (array) $settings->sort_options() as $value => $label ) {
				$sorts[] = array( 'value' => (string) $value, 'label' => (string) $label );
			}
		}

		$tutor_filters = array();
		$add( $tutor_filters, 'enable_tutor_teaching_category_filter', 'category', 'tcsp_tutor_category[]', 'multi', __( 'Teaching category', $t ), $this->term_choices( 'course-category' ) );
		$add( $tutor_filters, 'enable_tutor_course_type_filter', 'course_type', 'tcsp_tutor_course_type[]', 'multi', __( 'Course type', $t ), $this->ui_choices( 'course_type_choices' ) );
		$add( $tutor_filters, 'enable_tutor_language_filter', 'language', 'tcsp_tutor_language[]', 'multi', __( 'Languages', $t ), $this->ui_choices( 'profile_language_choices' ) );
		$add( $tutor_filters, 'enable_tutor_tutoring_type_filter', 'type', 'tcsp_tutor_type[]', 'multi', __( 'Tutoring type', $t ), $this->ui_choices( 'profile_tutoring_choices' ) );
		$add( $tutor_filters, 'enable_tutor_wilaya_filter', 'wilaya', 'tcsp_tutor_wilaya[]', 'multi', __( 'Wilaya', $t ), $this->ui_choices( 'wilaya_filter_choices' ) );
		$add( $tutor_filters, 'enable_tutor_municipality_filter', 'municipality', 'tcsp_tutor_municipality[]', 'multi', __( 'Municipality', $t ), $this->ui_choices( 'municipality_filter_choices' ) );
		$add( $tutor_filters, 'enable_tutor_rating_filter', 'rating', 'tcsp_tutor_rating', 'single', __( 'Minimum rating', $t ), array_map( function ( $n ) { return array( 'value' => (string) $n, 'label' => $n . '★+' ); }, array( 4, 3, 2, 1 ) ) );
		$add( $tutor_filters, 'enable_tutor_experience_filter', 'experience', 'tcsp_tutor_experience', 'single', __( 'Experience', $t ), array_map( function ( $n ) { return array( 'value' => (string) $n, 'label' => $n . '+ ' . __( 'years', $t ) ); }, array( 1, 3, 5, 10 ) ) );
		$add( $tutor_filters, 'enable_tutor_verified_filter', 'verified', 'tcsp_tutor_verified', 'single', __( 'Verified', $t ), array( array( 'value' => 'verified', 'label' => __( 'Verified only', $t ) ) ) );
		$add( $tutor_filters, 'enable_tutor_private_sessions_filter', 'private', 'tcsp_tutor_private', 'single', __( 'Private sessions', $t ), array( array( 'value' => '1', 'label' => __( 'Accepts private sessions', $t ) ) ) );

		return array(
			'courses' => array( 'sorts' => $sorts, 'param_sort' => 'tcsp_sort', 'filters' => $course_filters ),
			'tutors'  => array(
				'param_sort' => 'tcsp_tutor_sort',
				'sorts'      => array(
					array( 'value' => 'relevance', 'label' => __( 'Relevance', $t ) ),
					array( 'value' => 'rating', 'label' => __( 'Top rated', $t ) ),
					array( 'value' => 'students', 'label' => __( 'Most students', $t ) ),
					array( 'value' => 'courses', 'label' => __( 'Most courses', $t ) ),
					array( 'value' => 'experience', 'label' => __( 'Most experience', $t ) ),
					array( 'value' => 'newest', 'label' => __( 'Newest tutors', $t ) ),
				),
				'filters'    => $tutor_filters,
			),
		);
	}

	/* ------------------------------------------------------------------ courses */

	public function courses( WP_REST_Request $r ) {
		$page = max( 1, (int) $r->get_param( 'page' ) );
		$per  = min( 30, max( 1, (int) ( $r->get_param( 'per_page' ) ?: 12 ) ) );
		$q    = trim( (string) $r->get_param( 'q' ) );

		return OSA_Util::with_get(
			$r,
			array( 'tcsp_', 'tutor_course_filter' ),
			function () use ( $page, $per, $q ) {
				if ( '' !== $q ) {
					$_GET['s'] = wp_slash( $q ); // phpcs:ignore WordPress.Security.NonceVerification
				}
				$_GET['tcsp_api'] = '1'; // makes the search plugin treat this secondary query as its own // phpcs:ignore WordPress.Security.NonceVerification

				$query = new WP_Query(
					array(
						'post_type'           => 'courses',
						'post_status'         => 'publish',
						'posts_per_page'      => $per,
						'paged'               => $page,
						's'                   => $q,
						'ignore_sticky_posts' => true,
					)
				);
				$items = array();
				foreach ( $query->posts as $p ) {
					$card = OSA_Util::course_card( $p->ID );
					if ( $card ) {
						$items[] = $card;
					}
				}
				wp_reset_postdata();
				return OSA_Util::page( $items, $query->found_posts, $page, $per );
			}
		);
	}

	private function topics( $course_id, $enrolled, $can_preview_all ) {
		$out = array();
		if ( ! function_exists( 'tutor_utils' ) ) {
			return $out;
		}
		$topics = tutor_utils()->get_topics( $course_id );
		if ( ! $topics || empty( $topics->posts ) ) {
			return $out;
		}
		foreach ( $topics->posts as $topic ) {
			$items    = array();
			$contents = tutor_utils()->get_course_contents_by_topic( $topic->ID, -1 );
			foreach ( ( $contents && ! empty( $contents->posts ) ) ? $contents->posts : array() as $c ) {
				$type    = 'lesson' === $c->post_type ? 'lesson' : ( 'tutor_quiz' === $c->post_type ? 'quiz' : ( 'tutor_assignments' === $c->post_type ? 'assignment' : $c->post_type ) );
				$preview = (bool) get_post_meta( $c->ID, '_is_preview', true );
				$items[] = array(
					'id'        => (int) $c->ID,
					'type'      => $type,
					'title'     => wp_strip_all_tags( $c->post_title ),
					'preview'   => $preview,
					'locked'    => ! ( $enrolled || $preview || $can_preview_all ),
					'completed' => ( $enrolled && 'lesson' === $type && is_user_logged_in() ) ? (bool) tutor_utils()->is_completed_lesson( $c->ID, get_current_user_id() ) : false,
				);
			}
			$out[] = array( 'id' => (int) $topic->ID, 'title' => wp_strip_all_tags( $topic->post_title ), 'summary' => wp_strip_all_tags( (string) $topic->post_content ), 'items' => $items );
		}
		return $out;
	}

	private function lines( $raw ) {
		$raw = is_array( $raw ) ? implode( "\n", $raw ) : (string) $raw;
		return array_values( array_filter( array_map( 'trim', preg_split( '/\R/', wp_strip_all_tags( $raw ) ) ) ) );
	}

	public function course( WP_REST_Request $r ) {
		$id   = (int) $r['id'];
		$post = get_post( $id );
		$uid  = get_current_user_id();
		if ( ! $post || 'courses' !== $post->post_type ) {
			return OSA_Util::error( 'not_found', 'Course not found.', 404 );
		}
		$owner = $uid && ( (int) $post->post_author === $uid || current_user_can( 'manage_options' ) );
		if ( 'publish' !== $post->post_status && ! $owner ) {
			return OSA_Util::error( 'not_found', 'Course not found.', 404 );
		}

		$card     = OSA_Util::course_card( $id );
		$enrolled = $uid && function_exists( 'tutor_utils' ) ? (bool) tutor_utils()->is_enrolled( $id, $uid ) : false;

		$instructors = array();
		if ( function_exists( 'tutor_utils' ) ) {
			foreach ( (array) tutor_utils()->get_instructors_by_course( $id ) as $i ) {
				$instructors[] = array( 'id' => (int) $i->ID, 'name' => $i->display_name, 'avatar' => OSA_Util::avatar( $i->ID, 96 ) );
			}
		}
		if ( ! $instructors ) {
			$instructors[] = $card['instructor'];
		}

		$reviews = array();
		if ( function_exists( 'tutor_utils' ) ) {
			foreach ( (array) tutor_utils()->get_course_reviews( $id, 0, 10 ) as $rv ) {
				$reviews[] = array(
					'author' => isset( $rv->display_name ) ? $rv->display_name : ( $rv->comment_author ?? '' ),
					'rating' => (float) ( $rv->rating ?? 0 ),
					'text'   => wp_strip_all_tags( (string) ( $rv->comment_content ?? '' ) ),
					'date'   => $rv->comment_date ?? '',
				);
			}
		}

		$duration = get_post_meta( $id, '_course_duration', true );
		$progress = ( $enrolled && function_exists( 'tutor_utils' ) ) ? (int) tutor_utils()->get_course_completed_percent( $id, $uid ) : 0;

		// A pending offline order for this course (so the app can show "waiting for tutor confirmation").
		$pending = null;
		if ( $uid && ! $enrolled && class_exists( '\TutorInstructorOfflinePayment\Orders' ) ) {
			foreach ( (array) \TutorInstructorOfflinePayment\Orders::get_for_student( $uid, array( 'status' => 'any', 'limit' => 50 ) ) as $o ) {
				foreach ( (array) \TutorInstructorOfflinePayment\Orders::get_items( $o->id ) as $it ) {
					if ( (int) ( $it->id ?? 0 ) === $id && in_array( $o->payment_status, array( 'unpaid', 'pending' ), true ) ) {
						$pending = (int) $o->id;
					}
				}
			}
		}

		return array_merge(
			$card,
			array(
				'content'        => apply_filters( 'the_content', $post->post_content ), // phpcs:ignore WordPress.NamingConventions.PrefixAllGlobals
				'duration'       => is_array( $duration ) ? array_map( 'intval', $duration ) : null,
				'benefits'       => $this->lines( get_post_meta( $id, '_tutor_course_benefits', true ) ),
				'requirements'   => $this->lines( get_post_meta( $id, '_tutor_course_requirements', true ) ),
				'audience'       => $this->lines( get_post_meta( $id, '_tutor_course_target_audience', true ) ),
				'materials'      => $this->lines( get_post_meta( $id, '_tutor_course_material_includes', true ) ),
				'instructors'    => $instructors,
				'curriculum'     => $this->topics( $id, $enrolled, $owner ),
				'reviews'        => $reviews,
				'enrolled'       => $enrolled,
				'progress'       => $progress,
				'pending_order'  => $pending,
				'can_edit'       => $owner,
			)
		);
	}

	public function enroll_free( WP_REST_Request $r ) {
		$id  = (int) $r['id'];
		$uid = get_current_user_id();
		if ( 'courses' !== get_post_type( $id ) || 'publish' !== get_post_status( $id ) || ! function_exists( 'tutor_utils' ) ) {
			return OSA_Util::error( 'not_found', 'Course not found.', 404 );
		}
		$card = OSA_Util::course_card( $id );
		if ( ! $card['is_free'] ) {
			return OSA_Util::error( 'not_free', 'This course is paid. Use checkout.', 400 );
		}
		if ( tutor_utils()->is_enrolled( $id, $uid ) ) {
			return array( 'success' => true, 'already' => true );
		}
		$enrol = tutor_utils()->do_enroll( $id, 0, $uid );
		return $enrol ? array( 'success' => true ) : OSA_Util::error( 'enroll_failed', 'Could not enrol you in this course.', 500 );
	}

	public function my_courses( WP_REST_Request $r ) {
		$uid  = get_current_user_id();
		$page = max( 1, (int) $r->get_param( 'page' ) );
		$per  = 20;
		if ( ! function_exists( 'tutor_utils' ) ) {
			return OSA_Util::page( array(), 0, 1, $per );
		}
		$q     = tutor_utils()->get_enrolled_courses_by_user( $uid, array( 'publish', 'private' ), ( $page - 1 ) * $per, $per );
		$items = array();
		if ( $q && ! empty( $q->posts ) ) {
			foreach ( $q->posts as $p ) {
				$card             = OSA_Util::course_card( $p->ID );
				$card['progress'] = (int) tutor_utils()->get_course_completed_percent( $p->ID, $uid );
				$items[]          = $card;
			}
		}
		return OSA_Util::page( $items, $q ? (int) $q->found_posts : 0, $page, $per );
	}

	/* ------------------------------------------------------------------ learning */

	private function can_view_lesson( $lesson_id, $uid ) {
		$course_id = (int) tutor_utils()->get_course_id_by( 'lesson', $lesson_id );
		if ( ! $course_id ) {
			return array( false, 0 );
		}
		$ok = tutor_utils()->is_enrolled( $course_id, $uid )
			|| (bool) get_post_meta( $lesson_id, '_is_preview', true )
			|| current_user_can( 'manage_options' )
			|| (int) get_post_field( 'post_author', $course_id ) === $uid;
		return array( $ok, $course_id );
	}

	public function lesson( WP_REST_Request $r ) {
		$id  = (int) $r['id'];
		$uid = get_current_user_id();
		$p   = get_post( $id );
		if ( ! $p || 'lesson' !== $p->post_type || ! function_exists( 'tutor_utils' ) ) {
			return OSA_Util::error( 'not_found', 'Lesson not found.', 404 );
		}
		list( $ok, $course_id ) = $this->can_view_lesson( $id, $uid );
		if ( ! $ok ) {
			return OSA_Util::error( 'locked', 'Enrol in this course to open the lesson.', 403 );
		}

		$video = null;
		$v     = tutor_utils()->get_video( $id );
		if ( is_array( $v ) && ! empty( $v['source'] ) ) {
			$src = $v['source'];
			$url = '';
			if ( 'html5' === $src && ! empty( $v['source_video_id'] ) ) {
				$url = (string) wp_get_attachment_url( (int) $v['source_video_id'] );
			} elseif ( 'external_url' === $src ) {
				$url = (string) ( $v['source_external_url'] ?? '' );
			} elseif ( 'youtube' === $src ) {
				$url = (string) ( $v['source_youtube'] ?? '' );
			} elseif ( 'vimeo' === $src ) {
				$url = (string) ( $v['source_vimeo'] ?? '' );
			}
			$video = array( 'source' => $src, 'url' => $url, 'poster' => ! empty( $v['poster'] ) ? (string) $v['poster'] : null );
		}

		$files = array();
		foreach ( (array) tutor_utils()->get_attachments( $id ) as $a ) {
			$a = (object) $a;
			if ( ! empty( $a->url ) ) {
				$files[] = array( 'name' => (string) ( $a->name ?? $a->title ?? basename( $a->url ) ), 'url' => (string) $a->url, 'size' => (string) ( $a->size ?? '' ) );
			}
		}

		return array(
			'id'          => $id,
			'course_id'   => $course_id,
			'title'       => wp_strip_all_tags( $p->post_title ),
			'content'     => apply_filters( 'the_content', $p->post_content ), // phpcs:ignore WordPress.NamingConventions.PrefixAllGlobals
			'video'       => $video,
			'attachments' => $files,
			'completed'   => (bool) tutor_utils()->is_completed_lesson( $id, $uid ),
			'web_url'     => get_permalink( $id ),
		);
	}

	public function complete_lesson( WP_REST_Request $r ) {
		$id  = (int) $r['id'];
		$uid = get_current_user_id();
		if ( 'lesson' !== get_post_type( $id ) || ! function_exists( 'tutor_utils' ) ) {
			return OSA_Util::error( 'not_found', 'Lesson not found.', 404 );
		}
		list( $ok, $course_id ) = $this->can_view_lesson( $id, $uid );
		if ( ! $ok || ! tutor_utils()->is_enrolled( $course_id, $uid ) ) {
			return OSA_Util::error( 'locked', 'You are not enrolled in this course.', 403 );
		}
		do_action( 'tutor_lesson_completed_before', $id );
		tutor_utils()->mark_lesson_complete( $id, $uid );
		do_action( 'tutor_lesson_completed_after', $id, $uid );
		return array( 'success' => true, 'progress' => (int) tutor_utils()->get_course_completed_percent( $course_id, $uid ) );
	}

	/* ------------------------------------------------------------------ tutors */

	public function tutors( WP_REST_Request $r ) {
		$page = max( 1, (int) $r->get_param( 'page' ) );
		$per  = min( 30, max( 1, (int) ( $r->get_param( 'per_page' ) ?: 12 ) ) );
		$q    = trim( (string) $r->get_param( 'q' ) );

		$users = OSA_Util::with_get(
			$r,
			array( 'tcsp_tutor_' ),
			function () use ( $q ) {
				if ( '' !== $q ) {
					$_GET['tcsp_tutor_s'] = wp_slash( $q ); // phpcs:ignore WordPress.Security.NonceVerification
				}
				$req = OSA_Util::tcsp_tutor_call( 'request' );
				return is_array( $req ) ? OSA_Util::tcsp_tutor_call( 'all_instructors', $req ) : null;
			}
		);

		if ( ! is_array( $users ) ) { // search plugin missing or its internals changed: plain fallback
			$uq    = new WP_User_Query(
				array(
					'role'           => 'tutor_instructor',
					'search'         => $q ? '*' . $q . '*' : '',
					'search_columns' => array( 'display_name', 'user_login' ),
					'number'         => $per,
					'paged'          => $page,
					'orderby'        => 'display_name',
					'count_total'    => true,
				)
			);
			$items = array();
			foreach ( $uq->get_results() as $u ) {
				$items[] = OSA_Util::tutor_card( $u->ID );
			}
			return OSA_Util::page( $items, $uq->get_total(), $page, $per );
		}

		$total = count( $users );
		$slice = array_slice( $users, ( $page - 1 ) * $per, $per );
		$items = array();
		foreach ( $slice as $u ) {
			$card = OSA_Util::tutor_card( (int) $u->ID );
			if ( $card ) {
				$items[] = $card;
			}
		}
		return OSA_Util::page( $items, $total, $page, $per );
	}

	public function tutor( WP_REST_Request $r ) {
		$id = (int) $r['id'];
		$u  = get_userdata( $id );
		if ( ! $u || ! OSA_Util::user_can_instructor( $id ) ) {
			return OSA_Util::error( 'not_found', 'Tutor not found.', 404 );
		}
		$card = OSA_Util::tutor_card( $id, true );

		$ids = array_map( 'intval', (array) get_user_meta( $id, '_tutor_instructor_course_id', false ) );
		$q   = new WP_Query(
			array(
				'post_type'      => 'courses',
				'post_status'    => 'publish',
				'posts_per_page' => 30,
				'author'         => $id,
				'orderby'        => 'date',
				'order'          => 'DESC',
				'fields'         => 'ids',
			)
		);
		$ids = array_values( array_unique( array_merge( $q->posts, $ids ) ) );
		$courses = array();
		foreach ( $ids as $cid ) {
			if ( 'publish' === get_post_status( $cid ) && 'courses' === get_post_type( $cid ) ) {
				$courses[] = OSA_Util::course_card( $cid );
			}
		}
		$card['courses'] = $courses;
		return $card;
	}
}
