/******************************************************************************
** File Main Owner:   DOVE DPS Development Team
** File Description:  Rest Client functionality
**/
/*
{
* Copyright (c) 2010-2013 IBM Corporation
* All rights reserved.
*
* This program and the accompanying materials are made available under the
* terms of the Eclipse Public License v1.0 which accompanies this
* distribution, and is available at http://www.eclipse.org/legal/epl-v10.html
*
*  HISTORY
*
*  $Log: dove_rest_client.c $
*  $EndLog$
*
*  PORTING HISTORY
*
}
*/
#include "include.h"
#include "../inc/dove_rest_client.h"

#define RCLIENT_THD_NUM 8
#define RCLIENT_QUEUE_LEN 100
#define RCLIENT_THD_EVENT_READQ 1
#define SYNC_REST_CLIENT_DEFAULT_TIMEOUT_SEC 20
#define ASYNC_REST_CLIENT_TIMEOUT_SEC 3


typedef struct thd_cb
{
	long qid;
	long tid;
	struct event_base *evbase;
} thd_cb_t;

static thd_cb_t rclient_thd_cb[RCLIENT_THD_NUM];
static void syncprocess_callback(struct evhttp_request *request, void *args)
{
	struct event_base *base = (struct event_base *)((void **)args)[0];
	void (*cb)(struct evhttp_request *, void *) = (void (*)(struct evhttp_request *, void *))((void **)args)[1];
	if(NULL != cb)
	{
		(*cb)(request, ((void **)args)[2]);
	}
	if(NULL != base)
	{
		/* If base is not in loop, no any effect, nor the next loop iteration
		 * of the base */
		event_base_loopbreak(base);
	}
}

static void dove_rest_request_info_free(dove_rest_request_info_t *rinfo)
{
	if (NULL != rinfo->address)
	{
		free(rinfo->address);
	}
	if(NULL != rinfo->uri)
	{
		free(rinfo->uri);
	}
	if (NULL != rinfo->request)
	{
		if(NULL != rinfo->request->cb)
		{
			(*(rinfo->request->cb))(rinfo->request, rinfo->request->cb_arg);
		}
		evhttp_request_free(rinfo->request);
	}
}
static unsigned int get_rclient_thd_idx(const char *addr, unsigned short port)
{
	unsigned int v = 0;
	while(*addr != 0)
	{
		v ^= (unsigned int)(*addr);
		addr++;
	}
	v ^= (unsigned int) port;
	v %= RCLIENT_THD_NUM;
	return v;
}

/*
 ******************************************************************************
 * dove_rest_request_and_syncprocess --           *//**
 *
 * \brief This routine sends a HTTP request to a server, and waits until
 * corresponding reponse is returned or internal error occurs or timeouts
 * , then invokes the callback
 * - void (*cb)(struct evhttp_request *request, void *arg)) -
 * registered in parameter "request" to handle the response. The first 
 * parameter passed to callback is the a pointer to a evhttp_request data
 * structure, and is always the same one in the input parameter of this routine
 * In some internal error cases, NULL might be passed to the callback as the
 * first parameter.
 *
 * \param[in] address The IP address string of the server to which the request
 * is sent.
 * \param[in] port The TCP port of the server to which the request is sent.
 * \param[in] type The HTTP method.
 * \param[in] uri The URI of the HTTP request.
 * \param[in] request A pointer to a evhttp_request data structure.
 * The data structure should be allocated on heap. The ownership of the data
 * structure will passed to this routine, this routine is reponsible to free
 * it before returns. The caller should not dereference it after invoking
 * this routine.
 * \param[in] pre_alloc_base  The event base used for the HTTP operation. If
 * provided, this routine will use it for all related event operations and this
 * routine never free the pre_alloc_base when returns. If NULL, this routine
 * will allocated new event base, and use the new event base for all related
 * event operations and free it when this routine returns.
 * \param[in] timeout_in_secs Sets the timeout (in second) for events related
 * to this HTTP connection.A negative or zero value will set the timeout to
 * default value
 * 
 * \retval 0 Success
 * \retval -1 Failure
 *
 ******************************************************************************/
int dove_rest_request_and_syncprocess (
	const char *address, unsigned short port,
	enum evhttp_cmd_type type, const char *uri,
	struct evhttp_request *request , 
	struct event_base *pre_alloc_base, 
	int timeout_in_secs)
{
	struct event_base *base = NULL;
	struct evhttp_connection *conn = NULL;
	void *args[3];
	int ret = -1;
    char b64_enc_str[100] = {'\0'};
    char auth_header_value[100] = {'\0'};
    char uname[100] = {'\0'};


	if (NULL == address || NULL == uri || NULL == request)
	{
		if(request)
		{
			if(request->cb)
			{
				(*(request->cb))(request, request->cb_arg);
			}
			evhttp_request_free(request);
		}
		return -1;
	}
	do
	{
		if(NULL != pre_alloc_base)
		{
			base = pre_alloc_base;
		}
		else
		{
			base = event_base_new();
			if (NULL == base)
			{
				if(request->cb)
				{
					(*(request->cb))(request, request->cb_arg);
				}
				evhttp_request_free(request);
				break;
			}
		}
		conn = evhttp_connection_base_new(base, NULL, address, port);
		if (NULL == conn)
		{
			if(request->cb)
			{
				(*(request->cb))(request, request->cb_arg);
			}
			evhttp_request_free(request);
			break;
		}
		evhttp_connection_set_timeout(conn, (timeout_in_secs > 0) ? \
			timeout_in_secs : SYNC_REST_CLIENT_DEFAULT_TIMEOUT_SEC);
		/* Don't set retries more than 1, it will case memory leak and
		 * unexpected errors in libevent2 version 2.18 */
		evhttp_connection_set_retries(conn, 1);
		args[0] = base;
		args[1] = (void *)request->cb;
		args[2] = request->cb_arg;
		request->cb = syncprocess_callback;
		request->cb_arg = (void *)args;
		if(0 == strcmp(address,controller_location_ip_string))
		{
			memset(uname,0,100);
			strcat(uname,AUTH_HEADER_USERNAME);
			strcat(uname,":");
			strcat(uname,AUTH_HEADER_PASSWORD);
			memset(b64_enc_str,0,100);
			dps_base64_encode(uname,b64_enc_str);
			memset(auth_header_value,0,100);
			strcat(auth_header_value,"Basic ");
			strcat(auth_header_value,b64_enc_str);
			evhttp_add_header(evhttp_request_get_output_headers(request),
					"Authorization",auth_header_value );
		}
		/* We give ownership of the request to the connection */
		ret = evhttp_make_request(conn, request, type, uri);
		if(ret)
		{
			break;
		}
		ret = event_base_dispatch(base);
	} while (0);
	if (NULL != conn)
	{
		evhttp_connection_free(conn);
	}
	
	if(NULL == pre_alloc_base && NULL != base)
	{
		event_base_free(base);
	}
	return ret;
}

/*
 ******************************************************************************
 * dove_rest_request_and_asyncprocess --                *//**
 *
 * \brief This routine is used to send HTTP request to a server and process the
 * response asynchronously. The callback for the response is register in the
 * struct evhttp_request data structure pointered by the member request.
 * The callback 
 * - void (*cb)(struct evhttp_request *request, void *arg))-
 * will be invoked when corresponding response is received or interanl error
 * occurs.The first parameter passed to callback is the a pointer to a 
 * evhttp_request data structure, and is always the same one in the input
 * parameter of this routine. In some internal error cases, NULL might be 
 * passed to the callback as the first parameter.
 * The REST client infrastructure ensures first-in-first-serviced for the HTTP
 * request to the same destination. (determined by IP address and TCP port 
 * number)
 *
 * \param[in] rinfo A pointer to a dove_rest_request_info_t data structure. 
 * The data structure should be allocated on heap. The ownership of the data 
 * structure will passed to the REST client infrastructure, which is reponsible
 * to free it when completes. The caller should not dereference it after
 * invoking this routine. Member of data structure dove_rest_request_info_t:
 *	char *address; Pointer to IP address string of the server to which the 
 *   request is sent, should be allocated on heap, the infrastructure will call
 *   free() to release it when completes the process.
 *	char *uri; The URI which the HTTP request is accessing, should be allocated
 *   on heap, the infrastructure will call free() to release it when completes
 *   the process.
 *	unsigned short port; The tcp port of the server to which the request is
 *   sent.
 *	enum evhttp_cmd_type type; HTTP method.
 *	struct evhttp_request *request; A pointer to a evhttp_request data 
 *   structure. The data structure should be allocated on heap. The 
 *   infrastructure will release it when completes the process.
 * 
 * \retval 0 Success
 * \retval -1 Failure
 *
 ******************************************************************************/
int dove_rest_request_and_asyncprocess (dove_rest_request_info_t *rinfo)
{
	unsigned int thd_cb_idx;
	if(NULL == rinfo->address || NULL == rinfo->uri || NULL == rinfo->request)
	{
		dove_rest_request_info_free(rinfo);
		return -1;
	}
	thd_cb_idx = get_rclient_thd_idx(rinfo->address, rinfo->port);
	if (OSW_OK != queue_send(rclient_thd_cb[thd_cb_idx].qid, (char *)&rinfo, OSW_MAX_Q_MSG_LEN))
	{
		dove_rest_request_info_free(rinfo);
		return -1;
	}
	send_event(rclient_thd_cb[thd_cb_idx].tid, RCLIENT_THD_EVENT_READQ);
	return 0;
}

static void dove_rest_client_main (void *arg)
{
	unsigned int u4Event;
	long idx = (long)arg;
	dove_rest_request_info_t *rinfo;

	Py_Initialize();
	log_info(RESTHandlerLogLevel, "Enter");
	while(1)
	{
		recv_event(rclient_thd_cb[idx].tid, RCLIENT_THD_EVENT_READQ,
		           (unsigned int)(OSW_WAIT | OSW_EV_ANY), &u4Event);
		
		if ((u4Event & RCLIENT_THD_EVENT_READQ) == RCLIENT_THD_EVENT_READQ) 
		{
			while (queue_receive(rclient_thd_cb[idx].qid, (char *)&rinfo,
			                     sizeof(&rinfo), OSW_NO_WAIT) == OSW_OK)
			{
				dove_rest_request_and_syncprocess(rinfo->address,
				                                  rinfo->port,
				                                  rinfo->type,
				                                  rinfo->uri,
				                                  rinfo->request,
				                                  rclient_thd_cb[idx].evbase,
				                                  ASYNC_REST_CLIENT_TIMEOUT_SEC);
				/* The ownership of request is given to dove_rest_request_and_syncprocess */
				rinfo->request = NULL;
				dove_rest_request_info_free(rinfo);
			}
		}
	}
	log_info(RESTHandlerLogLevel, "Exit");
	Py_Finalize();
	return;
}

int dove_rest_client_init(void)
{
	int ret = 0;
	int i;
	char thdnamebuf[16];
	for (i = 0; i < RCLIENT_THD_NUM; i++)
	{
		rclient_thd_cb[i].evbase = event_base_new();
		if(NULL == rclient_thd_cb[i].evbase)
		{
			ret = -1;
			break;
		}
		sprintf(thdnamebuf, "RC%d", i); 
		log_notice(RESTHandlerLogLevel,
		           "dove_rest_client_init: Loop %d, creating queue %s",
		           i, thdnamebuf);
		if (create_queue((const char *)thdnamebuf, OSW_MAX_Q_MSG_LEN,
		                 RCLIENT_QUEUE_LEN, &rclient_thd_cb[i].qid) != OSW_OK)
		{
			log_notice(RESTHandlerLogLevel,
			           "dove_rest_client_init: Loop %d, create queue %s failed",
			           i, thdnamebuf);
			ret = -1;
			break;
		}
		log_notice(RESTHandlerLogLevel,
		           "dove_rest_client_init: Loop %d, creating task %s",
		           i, thdnamebuf);
		if (create_task((const char *)thdnamebuf, 0, OSW_DEFAULT_STACK_SIZE,
		                dove_rest_client_main, (void *)((long)i),
		                &rclient_thd_cb[i].tid) != OSW_OK)
		{
			log_notice(RESTHandlerLogLevel,
			           "dove_rest_client_init: Loop %d, create task %s failed",
			           i, thdnamebuf);
			ret = -1;
			break;
		}
		log_notice(RESTHandlerLogLevel,
		           "dove_rest_client_init: Loop %d, finished", i);
	}
	return ret;
}

