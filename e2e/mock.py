# 에뮬레이터 시험용 가짜 OpenAI 호환 서버. 에뮬레이터 안에서는 10.0.2.2 로 보인다.
# 첫 질문엔 bash 블록을, 명령 결과가 오면 마무리 답을 준다. 받은 요청 본문은 한 줄씩 기록한다.
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT, LOG = int(sys.argv[1]), sys.argv[2]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, kind, data):
        self.send_response(200)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.send('application/json', b'{"data":[{"id":"e2e/model"}]}')

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        with open(LOG, 'a', encoding='utf-8') as f:
            f.write(json.dumps(body, ensure_ascii=False) + '\n')
        if body['messages'][-1]['content'].startswith('명령 실행 결과'):
            text = '끝났습니다.'
        else:
            text = '만들게요.\n```bash\necho zako-e2e-ok > zako-e2e.txt && cat zako-e2e.txt\n```'
        events = [{'choices': [{'delta': {'content': text}}]}, {'choices': [], 'usage': {'total_tokens': 77}}]
        stream = ''.join('data: ' + json.dumps(e, ensure_ascii=False) + '\n\n' for e in events) + 'data: [DONE]\n\n'
        self.send('text/event-stream', stream.encode())


ThreadingHTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
