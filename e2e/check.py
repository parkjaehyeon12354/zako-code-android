# 가짜 서버 기록으로 확인: 앱이 두 번 물었고, 두 번째에 Termux 가 실행한 출력이 실려 왔는가.
import json
import sys

rows = [json.loads(line) for line in open(sys.argv[1], encoding='utf-8')]
assert len(rows) == 2, f'요청 수 {len(rows)}'
first, second = rows
assert first['stream_options'] == {'include_usage': True}, first
back = second['messages'][-1]['content']
assert 'zako-e2e-ok' in back and '종료 코드 0' in back, back
print('서버가 받은 명령 결과:', back.replace('\n', ' | '))
